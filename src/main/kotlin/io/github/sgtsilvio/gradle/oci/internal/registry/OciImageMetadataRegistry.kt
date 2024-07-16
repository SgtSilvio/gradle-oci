package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
internal class OciImageMetadataRegistry(val registryApi: OciRegistryApi) {

    fun pullMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<OciMultiArchImageMetadata> =
        registryApi.pullManifest(registry, imageReference.name, imageReference.tag.replaceFirst('!', ':'), credentials)
            .transformToMultiArchImageMetadata(registry, imageReference, credentials)

    fun pullMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
    ): Mono<OciMultiArchImageMetadata> =
        registryApi.pullManifest(registry, imageReference.name, digest, size, credentials)
            .transformToMultiArchImageMetadata(registry, imageReference, credentials)

    private fun Mono<OciData>.transformToMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<OciMultiArchImageMetadata> = flatMap { manifest ->
        transformToMultiArchImageMetadata(registry, imageReference, manifest, credentials)
    }

    private fun transformToMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        manifest: OciData,
        credentials: Credentials?,
    ): Mono<OciMultiArchImageMetadata> = when (manifest.mediaType) {
        INDEX_MEDIA_TYPE -> transformIndexToMultiArchImageMetadata(
            registry,
            imageReference,
            manifest,
            credentials,
            MANIFEST_MEDIA_TYPE,
            CONFIG_MEDIA_TYPE,
            LAYER_MEDIA_TYPE_PREFIX,
        )

        MANIFEST_MEDIA_TYPE -> transformManifestToMultiArchImageMetadata(
            registry,
            imageReference,
            manifest,
            credentials,
            CONFIG_MEDIA_TYPE,
            LAYER_MEDIA_TYPE_PREFIX,
        )

        DOCKER_MANIFEST_LIST_MEDIA_TYPE -> transformIndexToMultiArchImageMetadata(
            registry,
            imageReference,
            manifest,
            credentials,
            DOCKER_MANIFEST_MEDIA_TYPE,
            DOCKER_CONFIG_MEDIA_TYPE,
            DOCKER_LAYER_MEDIA_TYPE,
        )

        DOCKER_MANIFEST_MEDIA_TYPE -> transformManifestToMultiArchImageMetadata(
            registry,
            imageReference,
            manifest,
            credentials,
            DOCKER_CONFIG_MEDIA_TYPE,
            DOCKER_LAYER_MEDIA_TYPE,
        )

        else -> throw IllegalStateException("unsupported manifest media type '${manifest.mediaType}'")
    }

    private fun transformIndexToMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        index: OciData,
        credentials: Credentials?,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<OciMultiArchImageMetadata> {
        val indexJsonObject = jsonObject(String(index.bytes))
        val indexAnnotations = indexJsonObject.getStringMapOrEmpty("annotations")
        val metadataMonoList = indexJsonObject.get("manifests") {
            asArray().toList {
                val (manifestDescriptorPlatform, manifestDescriptor) = asObject().decodeOciManifestDescriptor()
                if (manifestDescriptor.mediaType != manifestMediaType) { // TODO support nested index
                    Mono.empty()
                } else {
                    registryApi.pullManifest(
                        registry,
                        imageReference.name,
                        manifestDescriptor.digest,
                        manifestDescriptor.size.toInt(),
                        credentials,
                    ).flatMap { manifest ->
                        if (manifest.mediaType != manifestMediaType) {
                            throw IllegalStateException("media type in manifest descriptor ($manifestMediaType) and manifest (${manifest.mediaType}) do not match")
                        }
                        transformManifestToImageMetadata(
                            registry,
                            imageReference,
                            manifest,
                            manifestDescriptor.annotations,
                            indexAnnotations,
                            credentials,
                            configMediaType,
                            layerMediaTypePrefix,
                        )
                    }.doOnNext { (platform) ->
                        if ((manifestDescriptorPlatform != null) && (platform != manifestDescriptorPlatform)) {
                            throw IllegalStateException("platform in manifest descriptor ($manifestDescriptorPlatform) and config ($platform) do not match")
                        }
                    }
                }
            }
        }
        indexJsonObject.requireStringOrNull("mediaType", index.mediaType)
        indexJsonObject.requireLong("schemaVersion", 2)
        // the same order as in the manifest is guaranteed by mergeSequential
        return Flux.mergeSequential(metadataMonoList)
            // linked to preserve the platform order
            .collect({ LinkedHashMap<Platform, OciMetadata>() }) { map, (platform, metadata) ->
                if (map.putIfAbsent(platform, metadata) != null) {
                    throw IllegalStateException("duplicate platform in image index: $platform")
                }
            }
            .map { OciMultiArchImageMetadata(it, index.digest, index.bytes.size) }
    }

    private fun transformManifestToMultiArchImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        manifest: OciData,
        credentials: Credentials?,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<OciMultiArchImageMetadata> = transformManifestToImageMetadata(
        registry,
        imageReference,
        manifest,
        TreeMap(),
        TreeMap(),
        credentials,
        configMediaType,
        layerMediaTypePrefix,
    ).map { OciMultiArchImageMetadata(mapOf(it), manifest.digest, manifest.bytes.size) }

    private fun transformManifestToImageMetadata(
        registry: String,
        imageReference: OciImageReference,
        manifest: OciData,
        manifestDescriptorAnnotations: SortedMap<String, String>,
        indexAnnotations: SortedMap<String, String>,
        credentials: Credentials?,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<Pair<Platform, OciMetadata>> {
        val manifestJsonObject = jsonObject(String(manifest.bytes))
        val manifestAnnotations = manifestJsonObject.getStringMapOrEmpty("annotations")
        val configDescriptor = manifestJsonObject.get("config") { asObject().decodeOciDescriptor() }
        val layerDescriptors =
            manifestJsonObject.getOrNull("layers") { asArray().toList { asObject().decodeOciDescriptor() } }
                ?: emptyList()
        manifestJsonObject.requireStringOrNull("mediaType", manifest.mediaType)
        manifestJsonObject.requireLong("schemaVersion", 2)
        if ((configDescriptor.mediaType != configMediaType) || layerDescriptors.any { !it.mediaType.startsWith(layerMediaTypePrefix) }) {
            return Mono.empty()
        }
        return registryApi.pullBlobAsString(registry, imageReference.name, configDescriptor.digest, configDescriptor.size, credentials).map { config ->
            val configJsonObject = jsonObject(config)
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            val architecture = configJsonObject.getString("architecture")
            val author = configJsonObject.getStringOrNull("author")
            var arguments: List<String>? = null
            var entryPoint: List<String>? = null
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
                    arguments = getStringListOrNull("Cmd")
                    entryPoint = getStringListOrNull("Entrypoint")
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
                    ports = getStringKeySetOrEmpty("ExposedPorts")
                    configAnnotations = getStringMapOrEmpty("Labels")
                    stopSignal = getStringOrNull("StopSignal")
                    user = getStringOrNull("User")
                    volumes = getStringKeySetOrEmpty("Volumes")
                    workingDirectory = getStringOrNull("WorkingDir")
                }
            }
            val creationTime = configJsonObject.getInstantOrNull("created")
            val history = configJsonObject.getOrNull("history") {
                asArray().toList { asObject().decodeHistoryEntry() }
            }
            val os = configJsonObject.getString("os")
            val osFeatures = configJsonObject.getStringSetOrEmpty("os.features")
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
                throw IllegalStateException("count of layers in manifest (${layerDescriptors.size}) and diff_ids in config (${diffIds.size}) do not match")
            }
            val layerDescriptorsWithDiffIds = layerDescriptors.zip(diffIds) { descriptor, diffId ->
                OciLayerDescriptor(
                    normalizeLayerMediaType(descriptor.mediaType),
                    descriptor.digest,
                    descriptor.size,
                    diffId,
                    descriptor.annotations,
                )
            }
            val layers = if (history == null) {
                layerDescriptorsWithDiffIds.map { OciLayerMetadata(it, null, null, null, null) }
            } else {
                var i = 0
                val layers = history.map {
                    val descriptor = if (it.emptyLayer) null else {
                        if (i >= layerDescriptorsWithDiffIds.size) {
                            throw IllegalStateException("count of history entries with empty_layer=true (${i + 1}+) and layer descriptors (${layerDescriptorsWithDiffIds.size}) do not match")
                        }
                        layerDescriptorsWithDiffIds[i++]
                    }
                    OciLayerMetadata(descriptor, it.creationTime, it.author, it.createdBy, it.comment)
                }
                if (i < layerDescriptorsWithDiffIds.size) {
                    throw IllegalStateException("count of history entries with empty_layer=true ($i) and layer descriptors (${layerDescriptorsWithDiffIds.size}) do not match")
                }
                layers
            }
            Pair(
                Platform(os, architecture, variant, osVersion, osFeatures),
                OciMetadata(
                    imageReference,
                    creationTime,
                    author,
                    user,
                    ports,
                    environment,
                    entryPoint,
                    arguments,
                    volumes,
                    workingDirectory,
                    stopSignal,
                    configAnnotations,
                    configDescriptor.annotations,
                    manifestAnnotations,
                    manifestDescriptorAnnotations,
                    indexAnnotations,
                    layers,
                ),
            )
        }
    }

    private fun normalizeLayerMediaType(mediaType: String) = when (mediaType) {
        DOCKER_LAYER_MEDIA_TYPE -> GZIP_COMPRESSED_LAYER_MEDIA_TYPE
        else -> mediaType
    }

    private fun JsonObject.decodeOciDescriptor() = OciDescriptorImpl(
        getString("mediaType"),
        getOciDigest("digest"),
        getLong("size"),
        getStringMapOrEmpty("annotations"),
    ) // TODO order?
    // TODO support data

    private fun JsonObject.decodeOciManifestDescriptor() = Pair(
        getOrNull("platform") { asObject().decodePlatform() },
        decodeOciDescriptor(),
    ) // TODO order?

    private fun JsonObject.decodePlatform() = Platform(
        getString("os"),
        getString("architecture"),
        getStringOrNull("variant") ?: "",
        getStringOrNull("os.version") ?: "",
        getStringSetOrEmpty("os.features"),
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

    private fun JsonObject.getStringKeySetOrEmpty(key: String) =
        getOrNull(key) { asObject().toMap(TreeMap()) { asObject() }.keys.toSortedSet() } ?: TreeSet()

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

internal class OciMultiArchImageMetadata(
    val platformToMetadata: Map<Platform, OciMetadata>,
    val digest: OciDigest,
    val size: Int,
)
