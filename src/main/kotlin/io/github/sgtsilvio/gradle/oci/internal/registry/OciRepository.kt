package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.Capability
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.VersionedCapability
import io.github.sgtsilvio.gradle.oci.component.encodeComponent
import io.github.sgtsilvio.gradle.oci.internal.json.addArray
import io.github.sgtsilvio.gradle.oci.internal.json.addArrayIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.mapping.MappedComponent
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
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * @author Silvio Giebl
 */
class OciRepository(private val componentRegistry: OciComponentRegistry) {

    private var server: DisposableServer? = null

    fun start(port: Int) {
        server = HttpServer.create()
            .bindAddress { InetSocketAddress("localhost", port) }
            .handle(::handle)
            .bindNow()
    }

    fun stop() {
        server?.disposeNow()
    }

    private fun handle(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
        val segments = request.uri().substring(1).split('/')
        println(segments)
        if (segments[0] == "v1") {
            if (segments.size < 6) {
                response.sendNotFound()
            }
            val registryUri = try {
                URI(String(Base64.getUrlDecoder().decode(segments[1])))
            } catch (e: IllegalArgumentException) {
                return response.sendNotFound()
            } catch (e: URISyntaxException) {
                return response.sendNotFound()
            }
            if (request.method() == HttpMethod.GET) {
                val last = segments[segments.lastIndex]
                return when {
                    last.endsWith(".module") -> handleModule(
                        registryUri,
                        decodeGroup(segments, segments.size - 3),
                        segments[segments.lastIndex - 2],
                        segments[segments.lastIndex - 1],
                        response,
                    )

                    segments.size < 7 -> response.sendNotFound()
                    last == "oci-component.json" -> handleOciComponent(
                        registryUri,
                        decodeGroup(segments, segments.size - 4),
                        segments[segments.lastIndex - 3],
                        segments[segments.lastIndex - 2],
                        segments[segments.lastIndex - 1],
                        response,
                    )

                    segments.size < 9 -> response.sendNotFound()
                    segments[segments.lastIndex - 2] == "layer" -> handleLayer(
                        registryUri,
                        decodeGroup(segments, segments.size - 6),
                        segments[segments.lastIndex - 5],
                        segments[segments.lastIndex - 4],
                        segments[segments.lastIndex - 3],
                        try {
                            segments[segments.lastIndex - 1].toOciDigest()
                        } catch (e: IllegalArgumentException) {
                            return response.sendNotFound()
                        },
                        try {
                            last.toLong()
                        } catch (e: NumberFormatException) {
                            return response.sendNotFound()
                        },
                        response,
                    )

                    else -> response.sendNotFound()
                }
            }
            return response.sendNotFound()
        }
        return response.sendNotFound()
    }

    private fun decodeGroup(segments: List<String>, toIndex: Int) = segments.subList(2, toIndex).joinToString(".")

    private fun handleModule(
        registryUri: URI,
        group: String,
        name: String,
        version: String,
        response: HttpServerResponse
    ): Publisher<Void> {
        val mappedComponent = map(group, name, version)
        val ociComponentFutures = mappedComponent.variants.map { (_, variant) ->
            getOciComponent(registryUri, mappedComponent, variant, null) // TODO credentials
        }
        val moduleJsonFuture = CompletableFuture.allOf(*ociComponentFutures.toTypedArray()).thenApply {
            val variantNameOciComponentPairs: List<Pair<String, OciComponent>> =
                mappedComponent.variants.keys.zip(ociComponentFutures) { variantName, ociComponentFuture ->
                    Pair(variantName, ociComponentFuture.get())
                }
            jsonObject {
                addString("formatVersion", "1.1")
                addObject("component") {
                    addString("group", group)
                    addString("module", name)
                    addString("version", version)
//                    addObject("attributes") {
//                        addString("org.gradle.status", "release")
//                    }
                }
                val layerDigestToVariantName = mutableMapOf<OciDigest, String>()
                addArray("variants", variantNameOciComponentPairs) { (variantName, ociComponent) ->
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
                                val encodedOciComponent = encodeComponent(ociComponent).toByteArray()
                                addString("name", "$name${if (variantName == "main") "" else "-$variantName"}-$version-oci-component.json")
                                addString("url", "$variantName/oci-component.json")
                                addNumber("size", encodedOciComponent.size.toLong())
                                addString("sha512", Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(encodedOciComponent)))
                                addString("sha256", Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(encodedOciComponent)))
                                addString("sha1", Hex.encodeHexString(MessageDigest.getInstance("SHA-1").digest(encodedOciComponent)))
                                addString("md5", Hex.encodeHexString(MessageDigest.getInstance("MD5").digest(encodedOciComponent)))
                            }
                            for ((digest, size) in ociComponent.collectLayerDigestSizePairs()) {
                                val layerVariantName = layerDigestToVariantName.putIfAbsent(digest, variantName) ?: variantName
                                addObject {
                                    addString("name", "oci-layer-${digest.algorithm.ociPrefix}-${digest.encodedHash}")
                                    addString("url", "$layerVariantName/layer/$digest/$size")
                                    addNumber("size", size)
                                    addString(digest.algorithm.ociPrefix, digest.encodedHash)
                                }
                            }
                        }
                        addArrayIfNotEmpty("capabilities", ociComponent.capabilities) { versionedCapability ->
                            addObject {
                                addString("group", versionedCapability.capability.group)
                                addString("name", versionedCapability.capability.name)
                                addString("version", versionedCapability.version)
                            }
                        }
                    }
                }
            }.toByteArray()
        }
        return response.header("Content-Type", "application/vnd.org.gradle.module+json") // TODO constants
            .sendByteArray(Mono.fromFuture(moduleJsonFuture))
    }

    private fun handleOciComponent(
        registryUri: URI,
        group: String,
        name: String,
        version: String,
        variantName: String,
        response: HttpServerResponse
    ): Publisher<Void> {
        val mappedComponent = map(group, name, version)
        val variant = mappedComponent.variants[variantName] ?: return response.sendNotFound()
        val componentJsonFuture = getOciComponent(registryUri, mappedComponent, variant, null).thenApply { ociComponent -> // TODO credentials
            encodeComponent(ociComponent).toByteArray()
        }
        return response.header("Content-Type", "text/plain").sendByteArray(Mono.fromFuture(componentJsonFuture))
    }

    private fun getOciComponent(
        registryUri: URI,
        mappedComponent: MappedComponent,
        variant: MappedComponent.Variant,
        credentials: OciRegistryApi.Credentials?,
    ): CompletableFuture<OciComponent> {
        // TODO cache
        return componentRegistry.pullComponent(
            registryUri.toString(),
            variant.imageName,
            variant.tagName,
            variant.capabilities.ifEmpty { sortedSetOf(VersionedCapability(Capability(mappedComponent.group, mappedComponent.name), mappedComponent.version)) },
            credentials,
        )
    }

    private fun handleLayer(
        registryUri: URI,
        group: String,
        name: String,
        version: String,
        variantName: String,
        digest: OciDigest,
        size: Long,
        response: HttpServerResponse
    ): Publisher<Void> {
//        if (size > 100000) {
//            return response.status(HttpResponseStatus(555, "digest mismatch")).send()
//        }
        val mappedComponent = map(group, name, version)
        val variant = mappedComponent.variants[variantName] ?: return response.sendNotFound()
        response.header("Content-Length", size.toString())
//        return Mono.fromFuture(
//            componentRegistry.registryApi.pullBlob(
//                registryUri.toString(),
//                variant.imageName,
//                digest,
//                size,
//                null, // TODO credentials
//            )
//        ).flatMapMany { response.sendFile(it) }
        return response.send(Mono.fromFuture(
            componentRegistry.registryApi.pullBlob(
                registryUri.toString(),
                variant.imageName,
                digest,
                size,
                null, // TODO credentials
                HttpResponse.BodySubscribers.ofPublisher(),
            )
        ).flatMapMany { byteBufferListPublisher -> JdkFlowAdapter.flowPublisherToFlux(byteBufferListPublisher) }
            .flatMap { byteBufferList -> Flux.fromIterable(byteBufferList) }
            .map { byteBuffer -> Unpooled.wrappedBuffer(byteBuffer) })
    }

    private fun map(group: String, name: String, version: String): MappedComponent {
        // TODO
        return MappedComponent(
            group,
            name,
            version,
            mapOf(
                "main" to MappedComponent.Variant(
//                    sortedSetOf(VersionedCapability(Capability(group, name), version)),
                    sortedSetOf(),
                    group.replace('.', '/') + '/' + name,
                    version,
                )
            ),
        )
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
}

fun main() {
    println(Base64.getUrlEncoder().encodeToString("https://registry-1.docker.io".toByteArray()))
    val ociRepository = OciRepository(OciComponentRegistry(OciRegistryApi()))
    ociRepository.start(12345)
    TimeUnit.MINUTES.sleep(3)
    ociRepository.stop()
}


/*
{
  "formatVersion": "1.1",
  "component": {
    "group": "<group>",
    "module": "<name>",
    "version": "<version>",
    "attributes": {
      "org.gradle.status": "release"
    }
  },
  "variants": [
    {
      "name": "ociImage|<featureVariant>OciImage",
      "attributes": {
        "io.github.sgtsilvio.gradle.distributiontype": "oci-image",
        "org.gradle.category": "distribution",
        "org.gradle.dependency.bundling": "external",
        "org.gradle.usage": "release" // maybe remove
      },
      "files": [
        {
          "name": "<name>[-<featureVariant>]-<version>-oci-component.json",
          "url": "<featureVariant>/oci-component.json",
          "size": <size>,
          "sha512": "<computed-128chars>",
          "sha256": "<computed-64chars>",
          "sha1": "<computed-40chars>",
          "md5": "<computed-32chars>"
        },
        {
          "name": "oci-layer-<(layer1-digest).replace(':', ',')>",
          "url": "layer/<layer1-digest>/<layer1-size>",
          "size": <layer1-size>,
          "<alg>": "<layer1-digest-without-alg>"
        },
        {
          "name": "oci-layer-<(layer2-digest).replace(':', ',')>",
          "url": "layer/<layer2-digest>/<layer2-size>",
          "size": <layer2-size>,
          "<alg>": "<layer2-digest-without-alg>"
        }
      ],
      "capabilities": [ // optional
        {
          "group": "<cap1-group>",
          "name": "<cap1-name>",
          "version": "<cap1-version>"
        },
        {
          "group": "<cap2-group>",
          "name": "<cap2-name>",
          "version": "<cap2-version>"
        }
      ]
    }
  ]
}

REST API
/v1/{registryUri}/{group}/{name}/{version}/module.json
/v1/{registryUri}/{group}/{name}/{version}/{featureVariant}/oci-component.json
/v1/{registryUri}/{group}/{name}/{version}/layer/{digest}-{size}

Example
/v1/{registryUri}/com/hivemq/hivemq-server/4.14.0/module.json
/v1/{registryUri}/com/hivemq/hivemq-server/4.14.0/main/oci-component.json
/v1/{registryUri}/com/hivemq/hivemq-server/4.14.0/dns/oci-component.json
/v1/{registryUri}/com/hivemq/hivemq-server/4.14.0/main/layer/sha265:abc123...abc123/13452
/v1/{registryUri}/com/hivemq/hivemq-server/4.14.0/dns/layer/sha265:abc456...abc456/325976
 */