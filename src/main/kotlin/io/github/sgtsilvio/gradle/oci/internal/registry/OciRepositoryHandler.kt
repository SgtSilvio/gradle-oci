package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.component.allLayers
import io.github.sgtsilvio.gradle.oci.component.encodeToJsonString
import io.github.sgtsilvio.gradle.oci.internal.cache.getMono
import io.github.sgtsilvio.gradle.oci.internal.createOciComponentClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciLayerClassifier
import io.github.sgtsilvio.gradle.oci.internal.createOciVariantName
import io.github.sgtsilvio.gradle.oci.internal.json.addArray
import io.github.sgtsilvio.gradle.oci.internal.json.addArrayIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.mapping.MappedComponent
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingData
import io.github.sgtsilvio.gradle.oci.mapping.map
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.gradle.oci.metadata.toOciDigest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import org.apache.commons.codec.binary.Hex
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
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

/**
 * @author Silvio Giebl
 */
class OciRepositoryHandler(
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
        if ((segments[0] == "v2") && (segments[1] == "repository")) {
            return handleRepository(request, segments.drop(2), response)
        }
        return response.sendNotFound()
    }

    private fun handleRepository(
        request: HttpServerRequest,
        segments: List<String>,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val isGET = when (request.method()) {
            HttpMethod.GET -> true
            HttpMethod.HEAD -> false
            else -> return response.sendNotFound()
        }
        if (segments.size < 5) {
            response.sendNotFound()
        }
        val registryUri = try {
            URI(String(Base64.getUrlDecoder().decode(segments[0])))
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        } catch (e: URISyntaxException) {
            return response.sendBadRequest()
        }
        if (segments.last().endsWith(".module")) {
            return handleModule(registryUri, segments, imageMappingData, credentials, isGET, response)
        }
        if (segments.size < 8) {
            return response.sendNotFound()
        }
        return handleComponentOrLayer(registryUri, segments, imageMappingData, credentials, isGET, response)
    }

    private fun handleModule(
        registryUri: URI,
        segments: List<String>,
        imageMappingData: OciImageMappingData,
        credentials: Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val componentId = decodeComponentId(segments, segments.lastIndex - 1)
        val mappedComponent = imageMappingData.map(componentId)
        return getOrHeadGradleModuleMetadata(registryUri, mappedComponent, credentials, isGET, response)
    }

    private fun handleComponentOrLayer(
        registryUri: URI,
        segments: List<String>,
        imageMappingData: OciImageMappingData,
        credentials: Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val lastIndex = segments.lastIndex
        val componentId = decodeComponentId(segments, lastIndex - 4)
        val variantName = segments[lastIndex - 3]
        val digest = try {
            segments[lastIndex - 2].toOciDigest()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val size = try {
            segments[lastIndex - 1].toLong()
        } catch (e: NumberFormatException) {
            return response.sendBadRequest()
        }
        val variant = imageMappingData.map(componentId).variants[variantName] ?: return response.sendNotFound()
        val last = segments[lastIndex]
        return when {
            last.endsWith("oci-component.json") -> getOrHeadComponent(registryUri, variant, digest, size.toInt(), credentials, isGET, response)
            last.endsWith("oci-layer") -> getOrHeadLayer(registryUri, variant.imageReference.name, digest, size, credentials, isGET, response)
            else -> response.sendNotFound()
        }
    }

    private fun decodeComponentId(segments: List<String>, versionIndex: Int) = VersionedCoordinates(
        segments.subList(1, versionIndex - 1).joinToString("."),
        segments[versionIndex - 1],
        segments[versionIndex],
    )

    private fun getOrHeadGradleModuleMetadata(
        registryUri: URI,
        mappedComponent: MappedComponent,
        credentials: Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val componentId = mappedComponent.componentId
        val variantNameComponentPairMonoList = mappedComponent.variants.map { (variantName, variant) ->
            getComponent(registryUri, variant, credentials).map { Pair(variantName, it) }
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
                val layerDigestToVariantNameAndCounter = mutableMapOf<OciDigest, Pair<String, Int>>()
                addArray("variants", variantNameComponentPairs) { (variantName, componentWithDigest) ->
                    val component = componentWithDigest.component
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
                                val componentName = "$fileNamePrefix-${createOciComponentClassifier(variantName)}.json"
                                addString("name", componentName)
                                addString("url", "$variantName/${componentWithDigest.digest}/${componentWithDigest.size}/$componentName")
                                addNumber("size", componentJson.size.toLong())
                                addString("sha512", Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(componentJson)))
                                addString("sha256", Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(componentJson)))
                                addString("sha1", Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(componentJson)))
                                addString("md5", Hex.encodeHexString(MessageDigest.getInstance("MD5").digest(componentJson)))
                            }
                            var counter = 0
                            for ((digest, size) in component.collectLayerDigestToSize()) {
                                addObject {
                                    var layerVariantNameAndCounter = layerDigestToVariantNameAndCounter[digest]
                                    if (layerVariantNameAndCounter == null) {
                                        layerVariantNameAndCounter = Pair(variantName, counter++)
                                        layerDigestToVariantNameAndCounter[digest] = layerVariantNameAndCounter
                                    }
                                    val (layerVariantName, layerCounter) = layerVariantNameAndCounter
                                    val layerName = "$fileNamePrefix-${createOciLayerClassifier(layerVariantName, layerCounter.toString())}"
                                    addString("name", layerName)
                                    addString("url", "$layerVariantName/$digest/$size/$layerName")
                                    addNumber("size", size)
                                    addString(digest.algorithm.ociPrefix, digest.encodedHash)
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
        return response.sendByteArray(moduleJsonMono, isGET)
    }

    private fun getOrHeadComponent(
        registryUri: URI,
        variant: MappedComponent.Variant,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val componentJsonMono = getComponent(registryUri, variant, digest, size, credentials).map { componentWithDigest ->
            componentWithDigest.component.encodeToJsonString().toByteArray()
        }
        response.header(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
        return response.sendByteArray(componentJsonMono, isGET)
    }

    private fun getComponent(
        registryUri: URI,
        variant: MappedComponent.Variant,
        credentials: Credentials?,
    ): Mono<OciComponentRegistry.ComponentWithDigest> {
        return componentCache.getMono(
            ComponentCacheKey(
                registryUri.toString(),
                variant.imageReference,
                null,
                -1,
                variant.capabilities,
                credentials?.hashed(),
            )
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
        variant: MappedComponent.Variant,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
    ): Mono<OciComponentRegistry.ComponentWithDigest> {
        return componentCache.getMono(
            ComponentCacheKey(
                registryUri.toString(),
                variant.imageReference,
                digest,
                size,
                variant.capabilities,
                credentials?.hashed(),
            )
        ) { (registry, imageReference, _, _, capabilities) ->
            componentRegistry.pullComponent(registry, imageReference, digest, size, capabilities, credentials)
        }
    }

    private fun getOrHeadLayer(
        registryUri: URI,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        response.header(HttpHeaderNames.CONTENT_LENGTH, size.toString())
        response.header(HttpHeaderNames.ETAG, digest.encodedHash)
        return if (isGET) {
            getLayer(registryUri, imageName, digest, size, credentials, response)
        } else {
            headLayer(registryUri, imageName, digest, credentials, response)
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

    private fun OciComponent.collectLayerDigestToSize(): LinkedHashMap<OciDigest, Long> {
        val digests = LinkedHashMap<OciDigest, Long>()
        for (layer in allLayers) {
            layer.descriptor?.let {
                digests[it.digest] = it.size
            }
        }
        return digests
    }

    private fun HttpServerResponse.sendBadRequest(): Mono<Void> = status(400).send()

    private fun HttpServerResponse.sendByteArray(data: Mono<ByteArray>, isGETelseHEAD: Boolean): Publisher<Void> {
        val dataAfterHeadersAreSet = data.doOnNext { bytes ->
            header(HttpHeaderNames.CONTENT_LENGTH, bytes.size.toString())
            val sha1 = Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(bytes))
            header(HttpHeaderNames.ETAG, sha1)
            header("x-checksum-sha1", sha1)
        }
        return sendByteArray(if (isGETelseHEAD) dataAfterHeadersAreSet else dataAfterHeadersAreSet.ignoreElement())
    }
}
