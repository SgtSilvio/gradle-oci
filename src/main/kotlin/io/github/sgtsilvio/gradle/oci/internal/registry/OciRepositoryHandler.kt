package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.internal.cache.getMono
import io.github.sgtsilvio.gradle.oci.internal.createOciIndexVariantName
import io.github.sgtsilvio.gradle.oci.internal.createOciMetadataClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciVariantName
import io.github.sgtsilvio.gradle.oci.internal.createPlatformPostfix
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.internal.string.escapeReplace
import io.github.sgtsilvio.gradle.oci.internal.string.unescapeReplace
import io.github.sgtsilvio.gradle.oci.mapping.OciImageComponent
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingData
import io.github.sgtsilvio.gradle.oci.mapping.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.mapping.map
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

/*
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <...>.module
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <variantName>/<digest>/<size>/<...>oci-component.json
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <variantName>/<digest>/<size>/<...>oci-layer

/v0.15/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <...>.module
/v0.15/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageReference)>/<digest>/<size>/<platform>/<...>.json
/v0.15/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageName)>/<digest>/<size>/<...>oci-layer
 */

internal const val OCI_REPOSITORY_VERSION = "v0.17"

/**
 * @author Silvio Giebl
 */
internal class OciRepositoryHandler(
    private val imageMetadataRegistry: OciImageMetadataRegistry,
    private val imageMappingData: OciImageMappingData,
    private val credentials: Credentials?,
) : BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    private val imageMetadataCache: AsyncCache<ImageMetadataCacheKey, OciMultiPlatformImageMetadata> =
        Caffeine.newBuilder().maximumSize(100).expireAfterAccess(1, TimeUnit.MINUTES).buildAsync()

    private data class ImageMetadataCacheKey(
        val registryUrl: URI,
        val imageReference: OciImageReference,
        val digest: OciDigest?,
        val size: Int,
        val credentials: HashedCredentials?,
    )

    override fun apply(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
        val segments = request.uri().substring(1).split('/')
        if (segments[0] == OCI_REPOSITORY_VERSION) {
            return handleRepository(request, segments.drop(1), response)
        }
        return response.sendNotFound()
    }

    private fun handleRepository(
        request: HttpServerRequest,
        segments: List<String>,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val isGet = when (request.method()) {
            HttpMethod.GET -> true
            HttpMethod.HEAD -> false
            else -> return response.sendNotFound()
        }
        if (segments.size < 5) {
            response.sendNotFound()
        }
        val registryUrl = try {
            URI(segments[0].unescapePathSegment())
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        } catch (e: URISyntaxException) {
            return response.sendBadRequest()
        }
        if ((segments.size == 5) && segments[4].endsWith(".module")) {
            val componentId = VersionedCoordinates(segments[1], segments[2], segments[3])
            val mappedComponent = imageMappingData.map(componentId)
            return getOrHeadGradleModuleMetadata(registryUrl, mappedComponent, credentials, isGet, response)
        }
        val last = segments.last()
        return when {
            last.endsWith(".json") -> getOrHeadMetadata(registryUrl, segments, isGet, response)
            last.endsWith("oci-layer") -> getOrHeadLayer(registryUrl, segments, isGet, response)
            else -> response.sendNotFound()
        }
    }

    private fun getOrHeadGradleModuleMetadata(
        registryUrl: URI,
        ociImageComponent: OciImageComponent,
        credentials: Credentials?,
        isGet: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        data class OciVariantsMetadata(
            val imageDefName: String,
            val capabilities: Set<VersionedCoordinates>,
            val platformToMetadata: Map<Platform, OciMetadata>,
            val digest: OciDigest,
            val size: Int,
        )
        val componentId = ociImageComponent.componentId
        val variantsMetadataMonoList = ociImageComponent.features.map { (featureName, feature) ->
            getMultiPlatformImageMetadata(registryUrl, feature.imageReference, credentials).map {
                OciVariantsMetadata(featureName, feature.capabilities, it.platformToMetadata, it.digest, it.size)
            }
        }
        // merge is used instead of zip as the latter one discards all elements if one Mono is empty
        val moduleJsonMono = Flux.mergeSequential(variantsMetadataMonoList).collectList().map { variantsMetadataList ->
            jsonObject {
                addString("formatVersion", "1.1")
                addObject("component") {
                    addString("group", componentId.group)
                    addString("module", componentId.name)
                    addString("version", componentId.version)
                    addObject("attributes") {
                        addString("org.gradle.status", "release")
                    }
                }
                val fileNamePrefix = "${componentId.name}-${componentId.version}-"
                addArray("variants") {
                    for ((imageDefName, capabilities, platformToMetadata, digest, size) in variantsMetadataList) {
                        if (platformToMetadata.size > 1) {
                            addObject {
                                addString("name", createOciIndexVariantName(imageDefName))
                                addOciVariantAttributes(
                                    OCI_IMAGE_INDEX_DISTRIBUTION_TYPE,
                                    platformToMetadata.keys.joinToString(";"),
                                )
                                addCapabilities(capabilities, componentId)
                            }
                        }
                        for ((platform, metadata) in platformToMetadata) {
                            addObject {
                                addString("name", createOciVariantName(imageDefName, platform))
                                addOciVariantAttributes(OCI_IMAGE_DISTRIBUTION_TYPE, platform.toString())
                                addCapabilities(capabilities, componentId)
                                addArray("files") {
                                    addObject {
                                        val metadataJson = metadata.encodeToJsonString().toByteArray()
                                        val metadataName = fileNamePrefix + createOciMetadataClassifier(imageDefName) + createPlatformPostfix(platform) + ".json"
                                        val escapedImageReference = metadata.imageReference.toString().escapePathSegment()
                                        addString("name", metadataName)
                                        addString("url", "$escapedImageReference/$digest/$size/$platform/$metadataName")
                                        addNumber("size", metadataJson.size.toLong())
                                        addString("sha512", DigestUtils.sha512Hex(metadataJson))
                                        addString("sha256", DigestUtils.sha256Hex(metadataJson))
                                        addString("sha1", DigestUtils.sha1Hex(metadataJson))
                                        addString("md5", DigestUtils.md5Hex(metadataJson))
                                    }
                                    val escapedImageName = metadata.imageReference.name.escapePathSegment()
                                    val digestCounts = HashMap<OciDigest, Int>()
                                    for (layerMetadata in metadata.layers) {
                                        layerMetadata.descriptor?.let { layerDescriptor ->
                                            addObject {
                                                val layerDigest = layerDescriptor.digest
                                                val algorithmId = layerDigest.algorithm.id
                                                val encodedHash = layerDigest.encodedHash
                                                val layerSize = layerDescriptor.size
                                                val count = digestCounts[layerDigest] ?: 0
                                                digestCounts[layerDigest] = count + 1
                                                val layerName = buildString {
                                                    append(fileNamePrefix)
                                                    append(algorithmId)
                                                    append('!')
                                                    append(encodedHash.take(5))
                                                    append("..")
                                                    append(encodedHash.takeLast(5))
                                                    if (count != 0) {
                                                        append("-dup").append(count)
                                                    }
                                                    append("-oci-layer")
                                                }
                                                addString("name", layerName + mapLayerMediaTypeToExtension(layerDescriptor.mediaType))
                                                addString("url", "$escapedImageName/$layerDigest/$layerSize/$layerName")
                                                addNumber("size", layerSize)
                                                addString(algorithmId, encodedHash)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.toByteArray()
        }
        response.header(HttpHeaderNames.CONTENT_TYPE, "application/vnd.org.gradle.module+json") // TODO constants
        return response.sendByteArray(moduleJsonMono, isGet)
    }

    private fun getOrHeadMetadata(
        registryUrl: URI,
        segments: List<String>,
        isGet: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        if (segments.size != 9) {
            return response.sendNotFound()
        }
        val imageReference = try {
            segments[4].unescapePathSegment().toOciImageReference()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val digest = try {
            segments[5].toOciDigest()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val size = try {
            segments[6].toInt()
        } catch (e: NumberFormatException) {
            return response.sendBadRequest()
        }
        val platform = try {
            segments[7].toPlatform()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val metadataJsonMono =
            getMultiPlatformImageMetadata(registryUrl, imageReference, digest, size, credentials).handle { it, sink ->
                val metadata = it.platformToMetadata[platform]
                if (metadata == null) {
                    response.status(400)
                } else {
                    sink.next(metadata.encodeToJsonString().toByteArray())
                }
            }
        response.header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
        return response.sendByteArray(metadataJsonMono, isGet)
    }

    private fun getOrHeadLayer(
        registryUrl: URI,
        segments: List<String>,
        isGet: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        if (segments.size != 8) {
            return response.sendNotFound()
        }
        val imageName = try {
            segments[4].unescapePathSegment()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val digest = try {
            segments[5].toOciDigest()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val size = try {
            segments[6].toLong()
        } catch (e: NumberFormatException) {
            return response.sendBadRequest()
        }
        response.header(HttpHeaderNames.CONTENT_LENGTH, size.toString())
        response.header(HttpHeaderNames.ETAG, digest.encodedHash)
        return if (isGet) {
            getLayer(registryUrl, imageName, digest, size, credentials, response)
        } else {
            headLayer(registryUrl, imageName, digest, credentials, response)
        }
    }

    private fun getMultiPlatformImageMetadata(
        registryUrl: URI,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<OciMultiPlatformImageMetadata> {
        return imageMetadataCache.getMono(
            ImageMetadataCacheKey(registryUrl, imageReference, null, -1, credentials?.hashed())
        ) { key ->
            imageMetadataRegistry.pullMultiPlatformImageMetadata(key.registryUrl, key.imageReference, credentials)
                .doOnNext {
                    imageMetadataCache.asMap().putIfAbsent(
                        key.copy(digest = it.digest, size = it.size),
                        CompletableFuture.completedFuture(it),
                    )
                }
        }
    }

    private fun getMultiPlatformImageMetadata(
        registryUrl: URI,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
    ): Mono<OciMultiPlatformImageMetadata> {
        return imageMetadataCache.getMono(
            ImageMetadataCacheKey(registryUrl, imageReference, digest, size, credentials?.hashed())
        ) { (registryUrl, imageReference) ->
            imageMetadataRegistry.pullMultiPlatformImageMetadata(registryUrl, imageReference, digest, size, credentials)
        }
    }

    private fun getLayer(
        registryUrl: URI,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> = response.send(
        imageMetadataRegistry.registryApi.pullBlob(
            registryUrl,
            imageName,
            digest,
            size,
            credentials,
            ByteBufFlux::retain,
        )
    )

    private fun headLayer(
        registryUrl: URI,
        imageName: String,
        digest: OciDigest,
        credentials: Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> = imageMetadataRegistry.registryApi.isBlobPresent(registryUrl, imageName, digest, credentials)
        .flatMap { present -> if (present) response.send() else response.sendNotFound() }

    private fun mapLayerMediaTypeToExtension(mediaType: String) = when (mediaType) {
        UNCOMPRESSED_LAYER_MEDIA_TYPE -> ".tar"
        GZIP_COMPRESSED_LAYER_MEDIA_TYPE -> ".tgz"
        else -> ""
    }

    private fun HttpServerResponse.sendBadRequest(): Mono<Void> = status(400).send()

    private fun HttpServerResponse.sendByteArray(data: Mono<ByteArray>, isGetElseHead: Boolean): Publisher<Void> {
        val dataAfterHeadersAreSet = data.doOnNext { bytes ->
            header(HttpHeaderNames.CONTENT_LENGTH, bytes.size.toString())
            val sha1 = DigestUtils.sha1Hex(bytes)
            header(HttpHeaderNames.ETAG, sha1)
            header("x-checksum-sha1", sha1)
        }
        return sendByteArray(if (isGetElseHead) dataAfterHeadersAreSet else dataAfterHeadersAreSet.ignoreElement())
    }
}

private fun JsonObjectStringBuilder.addOciVariantAttributes(distributionType: String, platformAttributeValue: String) =
    addObject("attributes") {
        addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, distributionType)
        addString(PLATFORM_ATTRIBUTE.name, platformAttributeValue)
        addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
        addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
//        addString(Usage.USAGE_ATTRIBUTE.name, "release")
    }

private fun JsonObjectStringBuilder.addCapabilities(
    capabilities: Set<VersionedCoordinates>,
    componentId: VersionedCoordinates,
) {
    if (capabilities != setOf(componentId)) {
        addArray("capabilities", capabilities) { capability ->
            addObject {
                addString("group", capability.group)
                addString("name", capability.name)
                addString("version", capability.version)
            }
        }
    }
}

internal fun String.escapePathSegment() = escapeReplace('*', '$').replace('/', '*')

internal fun String.unescapePathSegment() = replace('*', '/').unescapeReplace('*', '$')
