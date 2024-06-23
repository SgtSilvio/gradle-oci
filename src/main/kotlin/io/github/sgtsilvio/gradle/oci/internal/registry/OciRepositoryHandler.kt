package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.component.encodeToJsonString
import io.github.sgtsilvio.gradle.oci.internal.cache.getMono
import io.github.sgtsilvio.gradle.oci.internal.createOciLayerClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciMetadataClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciVariantInternalName
import io.github.sgtsilvio.gradle.oci.internal.createOciVariantName
import io.github.sgtsilvio.gradle.oci.internal.json.addArray
import io.github.sgtsilvio.gradle.oci.internal.json.addArrayIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.mapping.MappedComponent
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingData
import io.github.sgtsilvio.gradle.oci.mapping.map
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.zip
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

/v0.11/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <...>.module
/v0.11/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageReference)>/<digest>/<size>/<...>.json
/v0.11/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageName)>/<digest>/<size>/<...>oci-layer
 */

/**
 * @author Silvio Giebl
 */
internal class OciRepositoryHandler(
    private val metadataRegistry: OciMetadataRegistry,
    private val imageMappingData: OciImageMappingData,
    private val credentials: Credentials?,
) : BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    private val metadataCache: AsyncCache<ComponentCacheKey, List<OciMetadataRegistry.Metadata>> =
        Caffeine.newBuilder().maximumSize(100).expireAfterAccess(1, TimeUnit.MINUTES).buildAsync()

    private data class ComponentCacheKey(
        val registry: String,
        val imageReference: OciImageReference,
        val digest: OciDigest?,
        val size: Int,
        val credentials: HashedCredentials?,
    )

    override fun apply(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
        val segments = request.uri().substring(1).split('/')
        if (segments[0] == "v0.11") {
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
        val registryUri = try {
            URI(segments[0].unescapePathSegment())
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        } catch (e: URISyntaxException) {
            return response.sendBadRequest()
        }
        if ((segments.size == 5) && segments[4].endsWith(".module")) {
            val componentId = VersionedCoordinates(segments[1], segments[2], segments[3])
            val mappedComponent = imageMappingData.map(componentId)
            return getOrHeadGradleModuleMetadata(registryUri, mappedComponent, credentials, isGet, response)
        }
        val last = segments.last()
        return when {
            last.endsWith(".json") -> getOrHeadMetadata(registryUri, segments, isGet, response)
            last.endsWith("oci-layer") -> getOrHeadLayer(registryUri, segments, isGet, response)
            else -> response.sendNotFound()
        }
    }

    private fun getOrHeadMetadata(
        registryUri: URI,
        segments: List<String>,
        isGet: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        if (segments.size != 8) {
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
        val metadataJsonMono = getMetadata(registryUri, imageReference, digest, size, credentials).map { (metadata) ->
            metadata.encodeToJsonString().toByteArray()
        }
        response.header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
        return response.sendByteArray(metadataJsonMono, isGet)
    }

    private fun getOrHeadLayer(
        registryUri: URI,
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
            getLayer(registryUri, imageName, digest, size, credentials, response)
        } else {
            headLayer(registryUri, imageName, digest, credentials, response)
        }
    }

    private fun getOrHeadGradleModuleMetadata(
        registryUri: URI,
        mappedComponent: MappedComponent,
        credentials: Credentials?,
        isGet: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val componentId = mappedComponent.componentId
        val variantMetadataMonoList = mappedComponent.variants.map { (variantName, variant) ->
            getMetadataList(registryUri, variant.imageReference, credentials).map { metadataList ->
                Triple(variantName, variant.capabilities, metadataList)
            }
        }
        val moduleJsonMono = variantMetadataMonoList.zip { variantMetadataList ->
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
                val fileNamePrefix = "${componentId.name}-${componentId.version}"
                addArray("variants") {
                    for ((variantName, capabilities, metadataList) in variantMetadataList) {
                        addObject {
                            addString("name", createOciVariantName(variantName))
                            addObject("attributes") {
                                addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, OCI_IMAGE_DISTRIBUTION_TYPE)
                                addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
                                addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
                                addString(PLATFORM_ATTRIBUTE.name, MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE)
//                                addString(Usage.USAGE_ATTRIBUTE.name, "release")
                            }
                            if (capabilities != setOf(componentId)) {
                                addArrayIfNotEmpty("capabilities", capabilities) { capability ->
                                    addObject {
                                        addString("group", capability.group)
                                        addString("name", capability.name)
                                        addString("version", capability.version)
                                    }
                                }
                            }
                            addArray("dependencies") {
                                for ((_, platform) in metadataList) {
                                    addObject {
                                        addString("group", componentId.group)
                                        addString("module", componentId.name)
                                        addObject("version") {
                                            addString("requires", componentId.version)
                                        }
                                        addArray("requestedCapabilities", capabilities) { capability ->
                                            addObject {
                                                addString("group", capability.group)
                                                addString("name", capability.name + platform)
                                                addString("version", capability.version)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        for ((metadata, platform, digest, size) in metadataList) {
                            addObject {
                                addString("name", createOciVariantName(variantName, platform))
                                addObject("attributes") {
                                    addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, OCI_IMAGE_DISTRIBUTION_TYPE)
                                    addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
                                    addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
                                    addString(PLATFORM_ATTRIBUTE.name, platform.toString())
//                                    addString(Usage.USAGE_ATTRIBUTE.name, "release")
                                }
                                if (capabilities != setOf(componentId)) {
                                    addArrayIfNotEmpty("capabilities", capabilities) { capability ->
                                        addObject {
                                            addString("group", capability.group)
                                            addString("name", capability.name)
                                            addString("version", capability.version)
                                        }
                                    }
                                }
                                addArray("dependencies") {
                                    addObject {
                                        addString("group", componentId.group)
                                        addString("module", componentId.name)
                                        addObject("version") {
                                            addString("requires", componentId.version)
                                        }
                                        addArray("requestedCapabilities", capabilities) { capability ->
                                            addObject {
                                                addString("group", capability.group)
                                                addString("name", capability.name + platform)
                                                addString("version", capability.version)
                                            }
                                        }
                                    }
                                }
                            }
                            addObject {
                                addString("name", createOciVariantInternalName(variantName, platform))
                                addObject("attributes") {
                                    addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, OCI_IMAGE_DISTRIBUTION_TYPE)
                                    addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
                                    addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
//                                    addString(Usage.USAGE_ATTRIBUTE.name, "release")
                                }
                                addArray("capabilities", capabilities) { capability ->
                                    addObject {
                                        addString("group", capability.group)
                                        addString("name", capability.name + platform)
                                        addString("version", capability.version)
                                    }
                                }
                                addArray("files") {
                                    addObject {
                                        val metadataJson = metadata.encodeToJsonString().toByteArray()
                                        val metadataName = "$fileNamePrefix-${createOciMetadataClassifier(variantName)}$platform.json"
                                        val escapedImageReference = metadata.imageReference.toString().escapePathSegment()
                                        addString("name", metadataName)
                                        addString("url", "$escapedImageReference/$digest/$size/$metadataName")
                                        addNumber("size", metadataJson.size.toLong())
                                        addString("sha512", DigestUtils.sha512Hex(metadataJson))
                                        addString("sha256", DigestUtils.sha256Hex(metadataJson))
                                        addString("sha1", DigestUtils.sha1Hex(metadataJson))
                                        addString("md5", DigestUtils.md5Hex(metadataJson))
                                    }
                                    val escapedImageName = metadata.imageReference.name.escapePathSegment()
                                    for ((mediaType, layerDigest, layerSize) in metadata.layers.mapNotNull { it.descriptor }.distinctBy { it.digest }) {
                                        addObject {
                                            val algorithmId = layerDigest.algorithm.id
                                            val encodedHash = layerDigest.encodedHash
                                            val classifier = createOciLayerClassifier(
                                                "main",
                                                algorithmId + '!' + encodedHash.take(5) + ".." + encodedHash.takeLast(5),
                                            )
                                            val layerName = "$fileNamePrefix-$classifier"
                                            addString("name", layerName + mapLayerMediaTypeToExtension(mediaType))
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
            }.toByteArray()
        }
        response.header(HttpHeaderNames.CONTENT_TYPE, "application/vnd.org.gradle.module+json") // TODO constants
        return response.sendByteArray(moduleJsonMono, isGet)
    }

    private fun getMetadataList(
        registryUri: URI,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<List<OciMetadataRegistry.Metadata>> {
        return metadataCache.getMono(
            ComponentCacheKey(registryUri.toString(), imageReference, null, -1, credentials?.hashed())
        ) { key ->
            metadataRegistry.pullMetadataList(key.registry, key.imageReference, credentials).doOnNext { metadataList ->
                for (metadata in metadataList) {
                    metadataCache.asMap().putIfAbsent(
                        key.copy(digest = metadata.digest, size = metadata.size),
                        CompletableFuture.completedFuture(listOf(metadata)),
                    )
                }
            }
        }
    }

    private fun getMetadata(
        registryUri: URI,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
    ): Mono<OciMetadataRegistry.Metadata> {
        return metadataCache.getMono(
            ComponentCacheKey(registryUri.toString(), imageReference, digest, size, credentials?.hashed())
        ) { (registry, imageReference) ->
            metadataRegistry.pullMetadataList(registry, imageReference, digest, size, credentials)
        }.map { metadataList ->
            if (metadataList.size != 1) {
                throw IllegalStateException() // TODO message
            }
            metadataList[0]
        }
    }

    private fun getLayer(
        registryUri: URI,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> = response.send(
        metadataRegistry.registryApi.pullBlob(
            registryUri.toString(),
            imageName,
            digest,
            size,
            credentials,
            ByteBufFlux::retain,
        )
    )

    private fun headLayer(
        registryUri: URI,
        imageName: String,
        digest: OciDigest,
        credentials: Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> =
        metadataRegistry.registryApi.isBlobPresent(registryUri.toString(), imageName, digest, credentials)
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

internal fun String.escapePathSegment() = replace("$", "$0").replace("/", "$1")

internal fun String.unescapePathSegment() = replace("$1", "/").replace("$0", "$")
