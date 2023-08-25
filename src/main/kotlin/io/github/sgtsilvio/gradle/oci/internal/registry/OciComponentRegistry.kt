package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.mapping.OciImageReference
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
class OciComponentRegistry(val registryApi: OciRegistryApi) {

    data class ComponentWithDigest(val component: OciComponent, val digest: OciDigest, val size: Int)

    fun pullComponent(
        registry: String,
        imageReference: OciImageReference,
        capabilities: SortedSet<VersionedCoordinates>,
        credentials: Credentials?,
    ): Mono<ComponentWithDigest> =
        registryApi.pullManifest(registry, imageReference.name, imageReference.tag, credentials)
            .transformToComponent(registry, imageReference, credentials, capabilities)

    fun pullComponent(
        registry: String,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        capabilities: SortedSet<VersionedCoordinates>,
        credentials: Credentials?,
    ): Mono<ComponentWithDigest> = registryApi.pullManifest(registry, imageReference.name, digest, size, credentials)
        .transformToComponent(registry, imageReference, credentials, capabilities)

    private fun Mono<OciRegistryApi.Manifest>.transformToComponent(
        registry: String,
        imageReference: OciImageReference,
        credentials: Credentials?,
        capabilities: SortedSet<VersionedCoordinates>,
    ): Mono<ComponentWithDigest> = flatMap { manifest ->
        transformToComponent(registry, imageReference, manifest, credentials, capabilities).map { component ->
            ComponentWithDigest(component, manifest.digest, manifest.data.size)
        }
    }

    private fun transformToComponent(
        registry: String,
        imageReference: OciImageReference,
        manifest: OciRegistryApi.Manifest,
        credentials: Credentials?,
        capabilities: SortedSet<VersionedCoordinates>,
    ): Mono<OciComponent> = when (manifest.mediaType) {
        INDEX_MEDIA_TYPE -> transformIndexToComponent(
            registry,
            imageReference,
            manifest.data,
            credentials,
            capabilities,
            INDEX_MEDIA_TYPE,
            MANIFEST_MEDIA_TYPE,
            CONFIG_MEDIA_TYPE,
        )
        MANIFEST_MEDIA_TYPE -> transformManifestToComponent(
            registry,
            imageReference,
            manifest.data,
            credentials,
            capabilities,
            MANIFEST_MEDIA_TYPE,
            CONFIG_MEDIA_TYPE,
        )
        DOCKER_MANIFEST_LIST_MEDIA_TYPE -> transformIndexToComponent(
            registry,
            imageReference,
            manifest.data,
            credentials,
            capabilities,
            DOCKER_MANIFEST_LIST_MEDIA_TYPE,
            DOCKER_MANIFEST_MEDIA_TYPE,
            DOCKER_CONFIG_MEDIA_TYPE,
        )
        DOCKER_MANIFEST_MEDIA_TYPE -> transformManifestToComponent(
            registry,
            imageReference,
            manifest.data,
            credentials,
            capabilities,
            DOCKER_MANIFEST_MEDIA_TYPE,
            DOCKER_CONFIG_MEDIA_TYPE,
        )
        else -> throw IllegalStateException("unsupported manifest media type '${manifest.mediaType}'")
    }

    private fun transformIndexToComponent(
        registry: String,
        imageReference: OciImageReference,
        index: ByteArray,
        credentials: Credentials?,
        capabilities: SortedSet<VersionedCoordinates>,
        indexMediaType: String,
        manifestMediaType: String,
        configMediaType: String,
    ): Mono<OciComponent> {
        val indexJsonObject = jsonObject(String(index))
        val indexAnnotations = indexJsonObject.getStringMapOrNull("annotations") ?: TreeMap()
        val manifestFutures = indexJsonObject.get("manifests") {
            asArray().toList {
                val (platform, manifestDescriptor) = asObject().decodeOciManifestDescriptor(manifestMediaType)
                registryApi.pullManifest(registry, imageReference.name, manifestDescriptor.digest, manifestDescriptor.size.toInt(), credentials).flatMap { manifest ->
                    if (manifest.mediaType != manifestMediaType) { // TODO support nested index
                        throw IllegalArgumentException("expected \"$manifestMediaType\" as manifest media type, but is \"${manifest.mediaType}\"")
                    }
                    transformManifestToPlatformBundle(registry, imageReference.name, manifest.data, manifestDescriptor.annotations, credentials, manifestMediaType, configMediaType)
                }.map { platformBundlePair ->
                    if ((platform != null) && (platformBundlePair.first != platform)) {
                        throw IllegalArgumentException("platform in manifest descriptor ($platform) and config (${platformBundlePair.first}) do not match")
                    }
                    platformBundlePair
                }
            }
        }
        indexJsonObject.requireStringOrNull("mediaType", indexMediaType)
        indexJsonObject.requireLong("schemaVersion", 2)
        return Flux.merge(manifestFutures)
            .collect({ TreeMap<Platform, OciComponent.Bundle>() }) { map, platformBundle -> map += platformBundle }
            .map { platformBundles ->
                OciComponent(
                    imageReference,
                    capabilities,
                    OciComponent.PlatformBundles(platformBundles),
                    indexAnnotations,
                )
            }
    }

    private fun transformManifestToComponent(
        registry: String,
        imageReference: OciImageReference,
        manifest: ByteArray,
        credentials: Credentials?,
        capabilities: SortedSet<VersionedCoordinates>,
        manifestMediaType: String,
        configMediaType: String,
    ): Mono<OciComponent> {
        return transformManifestToPlatformBundle(
            registry,
            imageReference.name,
            manifest,
            TreeMap(),
            credentials,
            manifestMediaType,
            configMediaType,
        ).map { platformBundlePair ->
            OciComponent(
                imageReference,
                capabilities,
                OciComponent.PlatformBundles(sortedMapOf(platformBundlePair)),
                TreeMap(),
            )
        }
    }

    private fun transformManifestToPlatformBundle(
        registry: String,
        imageName: String,
        manifest: ByteArray,
        manifestDescriptorAnnotations: SortedMap<String, String>,
        credentials: Credentials?,
        manifestMediaType: String,
        configMediaType: String,
    ): Mono<Pair<Platform, OciComponent.Bundle>> {
        val manifestJsonObject = jsonObject(String(manifest))
        val manifestAnnotations = manifestJsonObject.getStringMapOrNull("annotations") ?: TreeMap()
        val configDescriptor = manifestJsonObject.get("config") { asObject().decodeOciDescriptor(configMediaType) }
        val layerDescriptors =
            manifestJsonObject.getOrNull("layers") { asArray().toList { asObject().decodeOciDescriptor() } } ?: listOf()
        manifestJsonObject.requireStringOrNull("mediaType", manifestMediaType)
        manifestJsonObject.requireLong("schemaVersion", 2)
        return registryApi.pullBlobAsString(registry, imageName, configDescriptor.digest, configDescriptor.size, credentials).map { config ->
            val configJsonObject = jsonObject(config)
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            val architecture = configJsonObject.getString("architecture")
            val author = configJsonObject.getStringOrNull("author")
            var command: OciComponent.Bundle.Command? = null
            var environment: SortedMap<String, String> = TreeMap()
            var ports: SortedSet<String> = TreeSet()
            var configAnnotations: SortedMap<String, String> = TreeMap()
            var stopSignal: String? = null
            var user: String? = null
            var volumes: SortedSet<String> = TreeSet()
            var workingDirectory: String? = null
            configJsonObject.getOrNull("config") {
                asObject().run {
                    // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                    val arguments = getStringListOrNull("Cmd")
                    val entryPoint = getStringListOrNull("Entrypoint")
                    command = if ((entryPoint == null) && (arguments == null)) null else OciComponent.Bundle.Command(entryPoint, arguments ?: listOf())
                    environment = getOrNull("Env") {
                        asArray().toMap(TreeMap()) {
                            val environmentString = asString()
                            val splitIndex = environmentString.indexOf('=')
                            if (splitIndex == -1) {
                                throw IllegalStateException("expected \"<name>=<value>\", but is \"$environmentString\"")
                            }
                            Pair(environmentString.substring(0, splitIndex), environmentString.substring(splitIndex + 1))
                        }
                    } ?: TreeMap()
                    ports = getStringSetOrNull("ExposedPorts") ?: TreeSet()
                    configAnnotations = getStringMapOrNull("Labels") ?: TreeMap()
                    stopSignal = getStringOrNull("StopSignal")
                    user = getStringOrNull("User")
                    volumes = getStringSetOrNull("Volumes") ?: TreeSet()
                    workingDirectory = getStringOrNull("WorkingDir")
                }
            }
            val creationTime = configJsonObject.getInstantOrNull("created")
            val history = configJsonObject.getOrNull("history") {
                asArray().toList { asObject().decodeHistoryEntry() }
            }
            val os = configJsonObject.getString("os")
            val osFeatures = configJsonObject.getStringSetOrNull("os.features") ?: TreeSet()
            val osVersion = configJsonObject.getStringOrNull("os.version") ?: ""
            val diffIds = configJsonObject.get("rootfs") {
                asObject().run {
                    // sorted for canonical json: diff_ids, type
                    requireString("type", "layers")
                    get("diff_ids") { asArray().toList { asString().toOciDigest() } }
                }
            }
            val variant = configJsonObject.getStringOrNull("variant") ?: ""

            if (layerDescriptors.size != diffIds.size) {
                throw IllegalStateException("manifest layers size (${layerDescriptors.size}) does not match config diff_ids size (${diffIds.size})")
            }
            var i = 0
            val layers = history?.map { historyEntry ->
                OciComponent.Bundle.Layer(
                    if (historyEntry.emptyLayer) null else run {
                        // TODO index check
                        val descriptor = layerDescriptors[i]
                        val diffId = diffIds[i]
                        i++
                        OciComponent.Bundle.Layer.Descriptor(
                            normalizeLayerMediaType(descriptor.mediaType),
                            descriptor.digest,
                            descriptor.size,
                            diffId,
                            descriptor.annotations,
                        )
                    },
                    historyEntry.creationTime,
                    historyEntry.author,
                    historyEntry.createdBy,
                    historyEntry.comment,
                )
                // TODO check i == layerDescriptors.size
            } ?: layerDescriptors.zip(diffIds) { descriptor, diffId ->
                OciComponent.Bundle.Layer(
                    OciComponent.Bundle.Layer.Descriptor(
                        normalizeLayerMediaType(descriptor.mediaType),
                        descriptor.digest,
                        descriptor.size,
                        diffId,
                        descriptor.annotations,
                    ),
                    null,
                    null,
                    null,
                    null,
                )
            }

            Pair(
                PlatformImpl(os, architecture, variant, osVersion, osFeatures),
                OciComponent.Bundle(
                    listOf(),
                    creationTime,
                    author,
                    user,
                    ports,
                    environment,
                    command,
                    volumes,
                    workingDirectory,
                    stopSignal,
                    configAnnotations,
                    configDescriptor.annotations,
                    manifestAnnotations,
                    manifestDescriptorAnnotations,
                    layers,
                ),
            )
        }
    }

    private fun normalizeLayerMediaType(mediaType: String) = when (mediaType) {
        DOCKER_LAYER_MEDIA_TYPE -> LAYER_MEDIA_TYPE
        else -> mediaType
    }

    private fun JsonObject.decodeOciDescriptor(mediaType: String): OciDescriptor {
        requireString("mediaType", mediaType)
        return decodeOciDescriptor()
    }

    private fun JsonObject.decodeOciDescriptor() = OciDescriptorImpl(
        getString("mediaType"),
        getOciDigest("digest"),
        getLong("size"),
        getStringMapOrNull("annotations") ?: TreeMap()
    ) // TODO order?
    // TODO support data

    private fun JsonObject.decodeOciManifestDescriptor(manifestMediaType: String) = Pair(
        getOrNull("platform") { asObject().decodePlatform() },
        decodeOciDescriptor(manifestMediaType), // TODO support nested index
    ) // TODO order?

    private fun JsonObject.decodePlatform(): Platform = PlatformImpl(
        getString("os"),
        getString("architecture"),
        getStringOrNull("variant") ?: "",
        getStringOrNull("os.version") ?: "",
        getStringSetOrNull("os.features") ?: TreeSet(),
    ) // TODO order?

    private class HistoryEntry(
        val creationTime: Instant?,
        val author: String?,
        val createdBy: String?,
        val comment: String?,
        val emptyLayer: Boolean,
    )

    private fun JsonObject.decodeHistoryEntry() = HistoryEntry(
        getInstantOrNull("created"),
        getStringOrNull("author"),
        getStringOrNull("created_by"),
        getStringOrNull("comment"),
        getBooleanOrNull("empty_layer") ?: false,
    )

    private fun JsonObject.getStringSetOrNull(key: String) = // TODO function name
        getOrNull(key) { asObject().toMap(TreeMap()) { asObject() }.keys.toSortedSet() }

    private fun JsonObject.requireString(key: String, expectedValue: String) {
        val actualValue = getString(key)
        if (actualValue != expectedValue) {
            throw JsonException.create(key, "expected \"$expectedValue\" but is \"$actualValue\"")
        }
    }

    private fun JsonObject.requireStringOrNull(key: String, expectedValue: String) {
        val actualValue = getStringOrNull(key)
        if ((actualValue != null) && (actualValue != expectedValue)) {
            throw JsonException.create(key, "expected \"$expectedValue\" but is \"$actualValue\"")
        }
    }

    private fun JsonObject.requireLong(key: String, expectedValue: Long) {
        val actualValue = getLong(key)
        if (actualValue != expectedValue) {
            throw JsonException.create(key, "expected \"$expectedValue\" but is \"$actualValue\"")
        }
    }
}
