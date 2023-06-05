package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.component.encodeToJsonString
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.mapping.*
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.toOciDigest
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpMethod
import org.apache.commons.codec.binary.Hex
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.reactivestreams.Publisher
import reactor.adapter.JdkFlowAdapter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

/**
 * @author Silvio Giebl
 */
class OciRepositoryHandler(private val componentRegistry: OciComponentRegistry) :
    BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    private data class OciComponentParameters(
        val registry: String,
        val imageReference: OciImageReference,
        val capabilities: SortedSet<VersionedCoordinates>,
        val credentials: OciRegistryApi.Credentials?,
    )

    private val componentCache: AsyncLoadingCache<OciComponentParameters, OciComponent> = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .buildAsync { (registry, imageReference, capabilities, credentials), _ ->
            componentRegistry.pullComponent(registry, imageReference, capabilities, credentials)
        }

    override fun apply(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
        val segments = request.uri().substring(1).split('/')
        if ((segments[0] == "v1") && (segments[1] == "repository")) {
            return handleRepository(request, segments.drop(2), response)
        }
        return response.sendNotFound()
    }

    private fun handleRepository(
        request: HttpServerRequest,
        segments: List<String>,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val context = jsonObject(request.requestHeaders()["Context"] ?: return response.sendBadRequest())
        val credentials = context.getOrNull("credentials") {
            asObject().run { OciRegistryApi.Credentials(getString("username"), getString("password")) }
        }
        val imageMappingData = context.getOrNull("imageMapping") { asObject().decodeOciImageNameMappingData() }
            ?: OciImageMappingData(TreeMap(), TreeMap(), TreeMap())

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
        val last = segments[segments.lastIndex]
        return when {
            last.endsWith(".module") -> handleRepositoryModule(registryUri, segments, imageMappingData, credentials, isGET, response)
            segments.size < 6 -> response.sendNotFound()
            last.endsWith("oci-component.json") -> handleRepositoryComponent(registryUri, segments, imageMappingData, credentials, isGET, response)
            last.startsWith("oci-layer-") -> handleRepositoryLayer(registryUri, segments, imageMappingData, credentials, isGET, response)
            else -> response.sendNotFound()
        }
    }

    private fun handleRepositoryModule(
        registryUri: URI,
        segments: List<String>,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val componentId = decodeComponentId(segments, segments.lastIndex - 1)
        return getOrHeadGradleModuleMetadata(registryUri, componentId, imageMappingData, credentials, isGET, response)
    }

    private fun handleRepositoryComponent(
        registryUri: URI,
        segments: List<String>,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val lastIndex = segments.lastIndex
        val componentId = decodeComponentId(segments, lastIndex - 2)
        val variantName = segments[lastIndex - 1]
        return getOrHeadComponent(registryUri, componentId, variantName, imageMappingData, credentials, isGET, response)
    }

    private fun handleRepositoryLayer(
        registryUri: URI,
        segments: List<String>,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val lastIndex = segments.lastIndex
        val componentId = decodeComponentId(segments, lastIndex - 2)
        val variantName = segments[lastIndex - 1]
        val last = segments[lastIndex]
        val digestStartIndex = "oci-layer-".length
        val digestEndIndex = last.lastIndexOf('-')
        if (digestEndIndex < digestStartIndex) {
            return response.sendBadRequest()
        }
        val digest = try {
            last.substring(digestStartIndex, digestEndIndex).toOciDigest()
        } catch (e: IllegalArgumentException) {
            return response.sendBadRequest()
        }
        val size = try {
            last.substring(digestEndIndex + 1).toLong()
        } catch (e: NumberFormatException) {
            return response.sendBadRequest()
        }
        return getOrHeadLayer(
            registryUri, componentId, variantName, digest, size, imageMappingData, credentials, isGET, response
        )
    }

    private fun decodeComponentId(segments: List<String>, versionIndex: Int) = VersionedCoordinates(
        Coordinates(
            segments.subList(1, versionIndex - 1).joinToString("."),
            segments[versionIndex - 1],
        ),
        segments[versionIndex],
    )

    private fun getOrHeadGradleModuleMetadata(
        registryUri: URI,
        componentId: VersionedCoordinates,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val mappedComponent = imageMappingData.map(componentId)
        val componentFutures = mappedComponent.variants.map { (variantName, variant) ->
            getComponent(registryUri, variant, credentials).thenApply { Pair(variantName, it) }
        }
        val moduleJsonFuture = CompletableFuture.allOf(*componentFutures.toTypedArray()).thenApply {
            val variantNameComponentPairs = componentFutures.map { it.get() }
            jsonObject {
                addString("formatVersion", "1.1")
                addObject("component") {
                    addString("group", componentId.coordinates.group)
                    addString("module", componentId.coordinates.name)
                    addString("version", componentId.version)
                    addObject("attributes") {
                        addString("org.gradle.status", "release")
                    }
                }
                val layerDigestToVariantName = mutableMapOf<OciDigest, String>()
                addArray("variants", variantNameComponentPairs) { (variantName, component) ->
                    addObject {
                        addString("name", if (variantName == "main") "ociImage" else variantName + "OciImage")
                        addObject("attributes") {
                            addString(DISTRIBUTION_TYPE_ATTRIBUTE.name, OCI_IMAGE_DISTRIBUTION_TYPE)
                            addString(Category.CATEGORY_ATTRIBUTE.name, DISTRIBUTION_CATEGORY)
                            addString(Bundling.BUNDLING_ATTRIBUTE.name, Bundling.EXTERNAL)
//                            addString(Usage.USAGE_ATTRIBUTE.name, "release")
                        }
                        addArray("files") {
                            addObject {
                                val componentJson = component.encodeToJsonString().toByteArray()
                                val componentName = "${componentId.coordinates.name}${if (variantName == "main") "" else "-$variantName"}-${componentId.version}-oci-component.json"
                                addString("name", componentName)
                                addString("url", "$variantName/$componentName")
                                addNumber("size", componentJson.size.toLong())
                                addString("sha512", Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(componentJson)))
                                addString("sha256", Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(componentJson)))
                                addString("sha1", Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(componentJson)))
                                addString("md5", Hex.encodeHexString(MessageDigest.getInstance("MD5").digest(componentJson)))
                            }
                            for ((digest, size) in component.collectLayerDigestSizePairs()) {
                                val layerVariantName = layerDigestToVariantName.putIfAbsent(digest, variantName) ?: variantName
                                addObject {
                                    val layerName = "oci-layer-$digest-$size"
                                    addString("name", layerName.replace(':', '-'))
                                    addString("url", "$layerVariantName/$layerName")
                                    addNumber("size", size)
                                    addString(digest.algorithm.ociPrefix, digest.encodedHash)
                                }
                            }
                        }
                        if (component.capabilities != setOf(componentId)) {
                            addArrayIfNotEmpty("capabilities", component.capabilities) { capability ->
                                addObject {
                                    addString("group", capability.coordinates.group)
                                    addString("name", capability.coordinates.name)
                                    addString("version", capability.version)
                                }
                            }
                        }
                    }
                }
            }.toByteArray()
        }
        response.header("Content-Type", "application/vnd.org.gradle.module+json") // TODO constants
        return response.sendByteArray(Mono.fromFuture(moduleJsonFuture), isGET)
    }

    private fun getOrHeadComponent(
        registryUri: URI,
        componentId: VersionedCoordinates,
        variantName: String,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val mappedComponent = imageMappingData.map(componentId)
        val variant = mappedComponent.variants[variantName] ?: return response.sendNotFound()
        val componentJsonFuture = getComponent(registryUri, variant, credentials).thenApply { component ->
            component.encodeToJsonString().toByteArray()
        }
        response.header("Content-Type", "application/json")
        return response.sendByteArray(Mono.fromFuture(componentJsonFuture), isGET)
    }

    private fun getComponent(
        registryUri: URI,
        variant: MappedComponent.Variant,
        credentials: OciRegistryApi.Credentials?,
    ): CompletableFuture<OciComponent> = componentCache[OciComponentParameters(
        registryUri.toString(),
        variant.imageReference,
        variant.capabilities,
        credentials,
    )]

    private fun getOrHeadLayer(
        registryUri: URI,
        componentId: VersionedCoordinates,
        variantName: String,
        digest: OciDigest,
        size: Long,
        imageMappingData: OciImageMappingData,
        credentials: OciRegistryApi.Credentials?,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val mappedComponent = imageMappingData.map(componentId)
        val variant = mappedComponent.variants[variantName] ?: return response.sendNotFound()
        response.header("Content-Length", size.toString())
        response.header("ETag", digest.encodedHash)
        return if (isGET) {
            getLayer(registryUri, variant.imageReference.name, digest, size, credentials, response)
        } else {
            headLayer(registryUri, variant.imageReference.name, digest, credentials, response)
        }
    }

    private fun getLayer(
        registryUri: URI,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: OciRegistryApi.Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> {
        return response.send(Mono.fromFuture(
            componentRegistry.registryApi.pullBlob(
                registryUri.toString(),
                imageName,
                digest,
                size,
                credentials,
                HttpResponse.BodySubscribers.ofPublisher(),
            )
        ).flatMapMany { byteBufferListPublisher -> JdkFlowAdapter.flowPublisherToFlux(byteBufferListPublisher) }
            .flatMap { byteBufferList -> Flux.fromIterable(byteBufferList) }
            .map { byteBuffer -> Unpooled.wrappedBuffer(byteBuffer) })
    }

    private fun headLayer(
        registryUri: URI,
        imageName: String,
        digest: OciDigest,
        credentials: OciRegistryApi.Credentials?,
        response: HttpServerResponse,
    ): Publisher<Void> {
        return Mono.fromFuture(
            componentRegistry.registryApi.isBlobPresent(registryUri.toString(), imageName, digest, credentials)
        ).flatMap { present -> if (present) response.send() else response.sendNotFound() }
    }

    private val OciComponent.allLayers // TODO deduplicate
        get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
            is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
            is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
        }

    private fun OciComponent.collectLayerDigestSizePairs(): LinkedHashSet<Pair<OciDigest, Long>> {
        val digests = LinkedHashSet<Pair<OciDigest, Long>>()
        for (layer in allLayers) {
            layer.descriptor?.let {
                digests += Pair(it.digest, it.size)
            }
        }
        return digests
    }

    private fun HttpServerResponse.sendBadRequest() = status(400).send()

    private fun HttpServerResponse.sendByteArray(data: Mono<ByteArray>, isGETelseHEAD: Boolean): Publisher<Void> {
        val dataAfterHeadersAreSet = data.doOnNext { bytes ->
            header("Content-Length", bytes.size.toString())
            val sha1 = Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(bytes))
            header("ETag", sha1)
            header("X-Checksum-Sha1", sha1)
        }
        return sendByteArray(if (isGETelseHEAD) dataAfterHeadersAreSet else dataAfterHeadersAreSet.ignoreElement())
    }
}
