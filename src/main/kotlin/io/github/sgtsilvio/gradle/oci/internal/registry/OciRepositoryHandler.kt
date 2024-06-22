package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.internal.cache.getMono
import io.github.sgtsilvio.gradle.oci.internal.createOciLayerClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciMetadataClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciVariantName
import io.github.sgtsilvio.gradle.oci.internal.json.*
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
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

/*
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <...>.module
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <variantName>/<digest>/<size>/<...>oci-component.json
/v2/repository/<base64(registryUrl)> / <group>/<name>/<version> / <variantName>/<digest>/<size>/<...>oci-layer

/v0.11/<base64(registryUrl)> / <group>/<name>/<version> / <...>.module
/v0.11/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageReference)>/<digest>/<size>/<base64(capabilities)>/<...>oci-component.json
/v0.11/<escapeSlash(registryUrl)> / <group>/<name>/<version> / <escapeSlash(imageName)>/<digest>/<size>/<...>oci-layer
 */

/**
 * @author Silvio Giebl
 */
internal class OciRepositoryHandler(
    private val componentRegistry: OciComponentRegistry,
    private val imageMappingData: OciImageMappingData,
    private val credentials: Credentials?,
) : BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    private val componentCache: AsyncCache<ComponentCacheKey, OciComponentRegistry.ComponentWithDigest> =
        Caffeine.newBuilder().maximumSize(100).expireAfterAccess(1, TimeUnit.MINUTES).buildAsync()

    private data class ComponentCacheKey(
        val registry: String,
        val imageReference: OciImageReference,
        val digest: OciDigest?,
        val size: Int,
        val capabilities: SortedSet<VersionedCoordinates>,
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
            last.endsWith("oci-component.json") -> getOrHeadMetadata(registryUri, segments, isGet, response)
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
        val capabilities = try {
            jsonArray(Base64.getUrlDecoder().decode(segments[7]).decodeToString()).decodeCapabilities()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val componentJsonMono = getComponent(
            registryUri,
            imageReference,
            digest,
            size,
            capabilities,
            credentials
        ).map { (component) -> component.encodeToJsonString().toByteArray() }
        response.header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
        return response.sendByteArray(componentJsonMono, isGet)
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
        val variantNameComponentPairMonoList = mappedComponent.variants.map { (variantName, variant) ->
            getComponent(registryUri, variant.imageReference, variant.capabilities, credentials).map {
                Pair(variantName, it)
            }
        }
        val moduleJsonMono = variantNameComponentPairMonoList.zip { variantNameComponentPairs ->
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
                addArray("variants", variantNameComponentPairs) { (variantName, componentWithDigest) ->
                    val (component, componentDigest, componentSize) = componentWithDigest
                    addObject {
                        addString("name", createOciVariantName(variantName))
                        addObject("attributes") {
                            addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, OCI_IMAGE_DISTRIBUTION_TYPE)
                            addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
                            addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
//                            addString(Usage.USAGE_ATTRIBUTE.name, "release")
                        }
                        addArray("files") {
                            addObject {
                                val componentJson = component.encodeToJsonString().toByteArray()
                                val componentName = "$fileNamePrefix-${createOciMetadataClassifier(variantName)}.json"
                                val escapedImageReference = component.imageReference.toString().escapePathSegment()
                                val capabilitiesBase64 = Base64.getUrlEncoder().encodeToString(jsonArray { encodeCapabilities(component.capabilities) }.toByteArray())
                                addString("name", componentName)
                                addString("url", "$escapedImageReference/$componentDigest/$componentSize/$capabilitiesBase64/$componentName")
                                addNumber("size", componentJson.size.toLong())
                                addString("sha512", DigestUtils.sha512Hex(componentJson))
                                addString("sha256", DigestUtils.sha256Hex(componentJson))
                                addString("sha1", DigestUtils.sha1Hex(componentJson))
                                addString("md5", DigestUtils.md5Hex(componentJson))
                            }
                            val escapedImageName = component.imageReference.name.escapePathSegment()
                            for ((mediaType, digest, size) in component.allLayerDescriptors.distinctBy { it.digest }) {
                                addObject {
                                    val algorithmId = digest.algorithm.id
                                    val encodedHash = digest.encodedHash
                                    val classifier = createOciLayerClassifier(
                                        "main",
                                        algorithmId + '!' + encodedHash.take(5) + ".." + encodedHash.takeLast(5),
                                    )
                                    val layerName = "$fileNamePrefix-$classifier"
                                    addString("name", layerName + mapLayerMediaTypeToExtension(mediaType))
                                    addString("url", "$escapedImageName/$digest/$size/$layerName")
                                    addNumber("size", size)
                                    addString(algorithmId, encodedHash)
                                }
                            }
                        }
                        if (component.capabilities != setOf(componentId)) {
                            addArrayIfNotEmpty("capabilities", component.capabilities) { capability ->
                                addObject {
                                    addString("group", capability.group)
                                    addString("name", capability.name)
                                    addString("version", capability.version)
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

    private fun getComponent(
        registryUri: URI,
        imageReference: OciImageReference,
        capabilities: SortedSet<VersionedCoordinates>,
        credentials: Credentials?,
    ): Mono<OciComponentRegistry.ComponentWithDigest> {
        return componentCache.getMono(
            ComponentCacheKey(registryUri.toString(), imageReference, null, -1, capabilities, credentials?.hashed())
        ) { key ->
            componentRegistry.pullComponent(key.registry, key.imageReference, key.capabilities, credentials)
                .doOnNext { componentWithDigest ->
                    componentCache.asMap().putIfAbsent(
                        key.copy(digest = componentWithDigest.digest, size = componentWithDigest.size),
                        CompletableFuture.completedFuture(componentWithDigest),
                    )
                }
        }
    }

    private fun getComponent(
        registryUri: URI,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        capabilities: SortedSet<VersionedCoordinates>,
        credentials: Credentials?,
    ): Mono<OciComponentRegistry.ComponentWithDigest> {
        return componentCache.getMono(
            ComponentCacheKey(registryUri.toString(), imageReference, digest, size, capabilities, credentials?.hashed())
        ) { (registry, imageReference, _, _, capabilities) ->
            componentRegistry.pullComponent(registry, imageReference, digest, size, capabilities, credentials)
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
        componentRegistry.registryApi.pullBlob(
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
        componentRegistry.registryApi.isBlobPresent(registryUri.toString(), imageName, digest, credentials)
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

internal fun JsonArrayStringBuilder.encodeCapabilities(capabilities: SortedSet<VersionedCoordinates>) {
    for (capability in capabilities) {
        addObject { encodeVersionedCoordinates(capability) }
    }
}

internal fun JsonArray.decodeCapabilities() = toSet(TreeSet()) { asObject().decodeVersionedCoordinates() }

internal fun String.escapePathSegment() = replace("$", "$0").replace("/", "$1")

internal fun String.unescapePathSegment() = replace("$1", "/").replace("$0", "$")
