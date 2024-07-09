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
internal class OciMetadataRegistry(val registryApi: OciRegistryApi) {

    data class Metadata(val metadata: OciMetadata, val platform: Platform, val digest: OciDigest, val size: Int) // TODO rename

    fun pullMetadataList(
        registry: String,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<List<Metadata>> =
        registryApi.pullManifest(registry, imageReference.name, imageReference.tag.replaceFirst('!', ':'), credentials)
            .transformToMetadataList(registry, imageReference, credentials)

    fun pullMetadataList(
        registry: String,
        imageReference: OciImageReference,
        digest: OciDigest,
        size: Int,
        credentials: Credentials?,
    ): Mono<List<Metadata>> = registryApi.pullManifest(registry, imageReference.name, digest, size, credentials)
        .transformToMetadataList(registry, imageReference, credentials)

    private fun Mono<OciRegistryApi.Manifest>.transformToMetadataList(
        registry: String,
        imageReference: OciImageReference,
        credentials: Credentials?,
    ): Mono<List<Metadata>> = flatMap { manifest ->
        transformToMetadataList(registry, imageReference, manifest, credentials)
    }

    private fun transformToMetadataList( // TODO inline?
        registry: String,
        imageReference: OciImageReference,
        manifest: OciRegistryApi.Manifest,
        credentials: Credentials?,
    ): Mono<List<Metadata>> = when (manifest.mediaType) {
        INDEX_MEDIA_TYPE -> transformIndexToMetadataList(
            registry,
            imageReference,
            manifest.data,
            credentials,
            INDEX_MEDIA_TYPE,
            MANIFEST_MEDIA_TYPE,
            CONFIG_MEDIA_TYPE,
            LAYER_MEDIA_TYPE_PREFIX,
        )
        MANIFEST_MEDIA_TYPE -> transformManifestToMetadataList(
            registry,
            imageReference,
            manifest.data,
            manifest.digest,
            credentials,
            MANIFEST_MEDIA_TYPE,
            CONFIG_MEDIA_TYPE,
            LAYER_MEDIA_TYPE_PREFIX,
        )
        DOCKER_MANIFEST_LIST_MEDIA_TYPE -> transformIndexToMetadataList(
            registry,
            imageReference,
            manifest.data,
            credentials,
            DOCKER_MANIFEST_LIST_MEDIA_TYPE,
            DOCKER_MANIFEST_MEDIA_TYPE,
            DOCKER_CONFIG_MEDIA_TYPE,
            DOCKER_LAYER_MEDIA_TYPE,
        )
        DOCKER_MANIFEST_MEDIA_TYPE -> transformManifestToMetadataList(
            registry,
            imageReference,
            manifest.data,
            manifest.digest,
            credentials,
            DOCKER_MANIFEST_MEDIA_TYPE,
            DOCKER_CONFIG_MEDIA_TYPE,
            DOCKER_LAYER_MEDIA_TYPE,
        )
        else -> throw IllegalStateException("unsupported manifest media type '${manifest.mediaType}'")
    }

    private fun transformIndexToMetadataList(
        registry: String,
        imageReference: OciImageReference,
        index: ByteArray,
        credentials: Credentials?,
        indexMediaType: String,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<List<Metadata>> {
        val indexJsonObject = jsonObject(String(index))
        val indexAnnotations = indexJsonObject.getStringMapOrEmpty("annotations")
        val metadataMonoList = indexJsonObject.get("manifests") {
            asArray().toList {
                val (platform, manifestDescriptor) = asObject().decodeOciManifestDescriptor()
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
                            throw IllegalArgumentException("media type in manifest descriptor ($manifestMediaType) and manifest (${manifest.mediaType}) do not match")
                        }
                        transformManifestToMetadata(
                            registry,
                            imageReference,
                            manifest.data,
                            manifest.digest,
                            manifestDescriptor.annotations,
                            indexAnnotations,
                            credentials,
                            manifestMediaType,
                            configMediaType,
                            layerMediaTypePrefix,
                        )
                    }.map { metadata ->
                        if ((platform != null) && (metadata.platform != platform)) {
                            throw IllegalArgumentException("platform in manifest descriptor ($platform) and config (${metadata.platform}) do not match")
                        }
                        metadata
                    }
                }
            }
        }
        indexJsonObject.requireStringOrNull("mediaType", indexMediaType)
        indexJsonObject.requireLong("schemaVersion", 2)
        // the same order as in the manifest is guaranteed by mergeSequential
        return Flux.mergeSequential(metadataMonoList).collectList()
    }

    private fun transformManifestToMetadataList(
        registry: String,
        imageReference: OciImageReference,
        manifest: ByteArray,
        digest: OciDigest,
        credentials: Credentials?,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<List<Metadata>> = transformManifestToMetadata(
        registry,
        imageReference,
        manifest,
        digest,
        TreeMap(),
        TreeMap(),
        credentials,
        manifestMediaType,
        configMediaType,
        layerMediaTypePrefix,
    ).map { listOf(it) }

    private fun transformManifestToMetadata(
        registry: String,
        imageReference: OciImageReference,
        manifest: ByteArray,
        digest: OciDigest,
        manifestDescriptorAnnotations: SortedMap<String, String>,
        indexAnnotations: SortedMap<String, String>,
        credentials: Credentials?,
        manifestMediaType: String,
        configMediaType: String,
        layerMediaTypePrefix: String,
    ): Mono<Metadata> {
        val manifestJsonObject = jsonObject(String(manifest))
        val manifestAnnotations = manifestJsonObject.getStringMapOrEmpty("annotations")
        val configDescriptor = manifestJsonObject.get("config") { asObject().decodeOciDescriptor() }
        val layerDescriptors =
            manifestJsonObject.getOrNull("layers") { asArray().toList { asObject().decodeOciDescriptor() } }
                ?: emptyList()
        manifestJsonObject.requireStringOrNull("mediaType", manifestMediaType)
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
                throw IllegalStateException("manifest layers size (${layerDescriptors.size}) does not match config diff_ids size (${diffIds.size})")
            }
            var i = 0
            val layers = history?.map { historyEntry ->
                OciMetadata.Layer(
                    if (historyEntry.emptyLayer) null else run {
                        // TODO index check
                        val descriptor = layerDescriptors[i]
                        val diffId = diffIds[i]
                        i++
                        OciMetadata.Layer.Descriptor(
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
                OciMetadata.Layer(
                    OciMetadata.Layer.Descriptor(
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

            Metadata(
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
                Platform(os, architecture, variant, osVersion, osFeatures),
                digest,
                manifest.size,
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
