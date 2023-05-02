package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.component.Capability
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.VersionedCapability
import io.github.sgtsilvio.gradle.oci.component.encodeComponent
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.mapping.OciImageName
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * @author Silvio Giebl
 */
class OciComponentRegistry(private val registryApi: RegistryApi) {

    fun pullComponent(
        registry: String,
        imageName: String,
        reference: String,
        credentials: RegistryApi.Credentials?,
    ): CompletableFuture<OciComponent> {
        val namespaceEndIndex = imageName.lastIndexOf('/') // TODO use OciImageName from the beginning
        val ociImageName = if (namespaceEndIndex == -1) {
            OciImageName("", imageName, reference)
        } else {
            OciImageName(imageName.substring(0, namespaceEndIndex), imageName.substring(namespaceEndIndex + 1), reference)
        }
        val capabilities = mapImageNameToCapabilities(ociImageName)
        return registryApi.pullManifest(registry, imageName, reference, credentials).thenCompose { manifest ->
            when (manifest.mediaType) {
                INDEX_MEDIA_TYPE -> transformIndexToComponent(
                    registry,
                    imageName,
                    manifest.data,
                    credentials,
                    capabilities,
                    INDEX_MEDIA_TYPE,
                    MANIFEST_MEDIA_TYPE,
                    CONFIG_MEDIA_TYPE,
                    LAYER_MEDIA_TYPE,
                )
                MANIFEST_MEDIA_TYPE -> transformManifestToComponent(
                    registry,
                    imageName,
                    manifest.data,
                    credentials,
                    capabilities,
                    MANIFEST_MEDIA_TYPE,
                    CONFIG_MEDIA_TYPE,
                    LAYER_MEDIA_TYPE,
                )
                DOCKER_MANIFEST_LIST_MEDIA_TYPE -> transformIndexToComponent(
                    registry,
                    imageName,
                    manifest.data,
                    credentials,
                    capabilities,
                    DOCKER_MANIFEST_LIST_MEDIA_TYPE,
                    DOCKER_MANIFEST_MEDIA_TYPE,
                    DOCKER_CONFIG_MEDIA_TYPE,
                    DOCKER_LAYER_MEDIA_TYPE,
                )
                DOCKER_MANIFEST_MEDIA_TYPE -> transformManifestToComponent(
                    registry,
                    imageName,
                    manifest.data,
                    credentials,
                    capabilities,
                    DOCKER_MANIFEST_MEDIA_TYPE,
                    DOCKER_CONFIG_MEDIA_TYPE,
                    DOCKER_LAYER_MEDIA_TYPE,
                )
                else -> throw IllegalStateException("unsupported manifest media type '${manifest.mediaType}'")
            }
        }
    }

    private fun transformIndexToComponent(
        registry: String,
        imageName: String,
        index: String,
        credentials: RegistryApi.Credentials?,
        capabilities: SortedSet<VersionedCapability>,
        indexMediaType: String,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaType: String,
    ): CompletableFuture<OciComponent> {
        val indexJsonObject = jsonObject(index)
        val indexAnnotations = indexJsonObject.getStringMapOrNull("annotations") ?: TreeMap()
        val manifestFutures = indexJsonObject.get("manifests") {
            asArray().toList {
                val (platform, manifestDescriptor) = asObject().decodeOciManifestDescriptor(manifestMediaType)
                registryApi.pullManifest(registry, imageName, manifestDescriptor.digest, manifestDescriptor.size, credentials).thenCompose { manifest ->
                    if (manifest.mediaType != manifestMediaType) { // TODO support nested index
                        throw IllegalArgumentException("expected \"$manifestMediaType\" as manifest media type, but is \"${manifest.mediaType}\"")
                    }
                    transformManifestToPlatformBundle(registry, imageName, manifest.data, manifestDescriptor.annotations, credentials, manifestMediaType, configMediaType, layerMediaType)
                }.thenApply { platformBundlePair ->
                    if (platformBundlePair.first != platform) {
                        throw IllegalArgumentException("platform in manifest descriptor ($platform) and config (${platformBundlePair.first}) do not match")
                    }
                    platformBundlePair
                }
            }
        }
        indexJsonObject.requireStringOrNull("mediaType", indexMediaType)
        indexJsonObject.requireLong("schemaVersion", 2)
        return CompletableFuture.allOf(*manifestFutures.toTypedArray()).thenApply {
            val platformBundles = manifestFutures.associateTo(TreeMap()) {
                @Suppress("BlockingMethodInNonBlockingContext")
                it.get()
            }
            OciComponent(
                capabilities,
                OciComponent.PlatformBundles(platformBundles),
                indexAnnotations,
            )
        }
    }

    private fun transformManifestToComponent(
        registry: String,
        imageName: String,
        manifest: String,
        credentials: RegistryApi.Credentials?,
        capabilities: SortedSet<VersionedCapability>,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaType: String,
    ): CompletableFuture<OciComponent> {
        return transformManifestToPlatformBundle(
            registry,
            imageName,
            manifest,
            TreeMap(),
            credentials,
            manifestMediaType,
            configMediaType,
            layerMediaType,
        ).thenApply { platformBundlePair ->
            OciComponent(capabilities, OciComponent.PlatformBundles(sortedMapOf(platformBundlePair)), TreeMap())
        }
    }

    private fun transformManifestToPlatformBundle(
        registry: String,
        imageName: String,
        manifest: String,
        manifestDescriptorAnnotations: SortedMap<String, String>,
        credentials: RegistryApi.Credentials?,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaType: String,
    ): CompletableFuture<Pair<Platform, OciComponent.Bundle>> {
        val manifestJsonObject = jsonObject(manifest)
        val manifestAnnotations = manifestJsonObject.getStringMapOrNull("annotations") ?: TreeMap()
        val configDescriptor = manifestJsonObject.get("config") { asObject().decodeOciDescriptor(configMediaType) }
        val layerDescriptors =
            manifestJsonObject.getOrNull("layers") { asArray().toList { asObject().decodeOciDescriptor(layerMediaType) } } ?: listOf() // TODO support other layer mediatype, needs support in OciComponent as well
        manifestJsonObject.requireStringOrNull("mediaType", manifestMediaType)
        manifestJsonObject.requireLong("schemaVersion", 2)
        return registryApi.pullBlobAsString(registry, imageName, configDescriptor.digest, configDescriptor.size, credentials).thenApply { config ->
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
                            descriptor.mediaType,
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
                        descriptor.mediaType,
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
                    listOf(),
                    layers,
                ),
            )
        }
    }

    // TODO proper mapping
    private fun mapImageNameToCapabilities(imageName: OciImageName): SortedSet<VersionedCapability> =
        sortedSetOf(VersionedCapability(Capability(imageName.namespace.replace('/', '.'), imageName.name), imageName.tag))

    private fun JsonObject.decodeOciDescriptor(mediaType: String): OciDescriptor {
        // TODO order?
        // TODO support data
        requireString("mediaType", mediaType)
        return OciDescriptorImpl(
            mediaType,
            getOciDigest("digest"),
            getLong("size"),
            getStringMapOrNull("annotations") ?: TreeMap()
        )
    }

    private fun JsonObject.decodeOciManifestDescriptor(manifestMediaType: String): Pair<Platform, OciDescriptor> = Pair(
        get("platform") { asObject().decodePlatform() },
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

fun main() {
    val componentRegistry = OciComponentRegistry(RegistryApi())
    println(
        encodeComponent(componentRegistry.pullComponent(
            "https://registry-1.docker.io",
            "library/registry",
            "2",
            null,
        ).get())
    )
}