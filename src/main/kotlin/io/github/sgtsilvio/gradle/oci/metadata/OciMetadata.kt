package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.component.OciMetadata
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.util.*

internal const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"
internal const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
internal const val CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
internal const val LAYER_MEDIA_TYPE_PREFIX = "application/vnd.oci.image.layer.v1"
internal const val UNCOMPRESSED_LAYER_MEDIA_TYPE = "$LAYER_MEDIA_TYPE_PREFIX.tar"
internal const val GZIP_COMPRESSED_LAYER_MEDIA_TYPE = "$LAYER_MEDIA_TYPE_PREFIX.tar+gzip"

internal fun createConfig(platform: Platform, metadataList: List<OciMetadata>): OciDataDescriptor {
    var user: String? = null
    val ports = TreeSet<String>()
    val environment = TreeMap<String, String>()
    var entryPoint = listOf<String>()
    var arguments = listOf<String>()
    val volumes = TreeSet<String>()
    var workingDirectory: String? = null
    var stopSignal: String? = null
    val annotations = TreeMap<String, String>()
    for (metadata in metadataList) {
        metadata.user?.let { user = it }
        ports.addAll(metadata.ports)
        environment += metadata.environment
        metadata.command?.let { command ->
            command.entryPoint?.let { entryPoint = it }
            arguments = command.arguments
        }
        volumes.addAll(metadata.volumes)
        metadata.workingDirectory?.let { workingDirectory = it }
        metadata.stopSignal?.let { stopSignal = it }
        annotations += metadata.configAnnotations
    }
    val lastMetadata = metadataList.last()
    val data = jsonObject {
        // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
        addString("architecture", platform.architecture)
        addStringIfNotNull("author", lastMetadata.author)
        addObject("config") {
            // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
            addArrayIfNotEmpty("Cmd", arguments)
            addArrayIfNotEmpty("Entrypoint", entryPoint)
            addArrayIfNotEmpty("Env", environment.map { "${it.key}=${it.value}" })
            addObjectIfNotEmpty("ExposedPorts", ports)
            addObjectIfNotEmpty("Labels", annotations)
            addStringIfNotNull("StopSignal", stopSignal)
            addStringIfNotNull("User", user)
            addObjectIfNotEmpty("Volumes", volumes)
            addStringIfNotNull("WorkingDir", workingDirectory)
        }
        addStringIfNotNull("created", lastMetadata.creationTime?.toString())
        addArray("history") {
            for (metadata in metadataList) {
                for (layer in metadata.layers) {
                    addObject {
                        // sorted for canonical json: author, comment, created, created_by, empty_layer
                        addStringIfNotNull("author", layer.author)
                        addStringIfNotNull("comment", layer.comment)
                        addStringIfNotNull("created", layer.creationTime?.toString())
                        addStringIfNotNull("created_by", layer.createdBy)
                        if (layer.descriptor == null) {
                            addBoolean("empty_layer", true)
                        }
                    }
                }
            }
        }
        addString("os", platform.os)
        addArrayIfNotEmpty("os.features", platform.osFeatures)
        addStringIfNotEmpty("os.version", platform.osVersion)
        addObject("rootfs") {
            // sorted for canonical json: diff_ids, type
            addArray("diff_ids") {
                for (metadata in metadataList) {
                    for (layer in metadata.layers) {
                        layer.descriptor?.let {
                            addString(it.diffId.toString())
                        }
                    }
                }
            }
            addString("type", "layers")
        }
        addStringIfNotEmpty("variant", platform.variant)
    }.toByteArray()
    return OciDataDescriptor(CONFIG_MEDIA_TYPE, data, lastMetadata.configDescriptorAnnotations) // TODO lastMetadata?
}

internal fun createManifest(configDescriptor: OciDescriptor, metadataList: List<OciMetadata>): OciDataDescriptor {
    val lastMetadata = metadataList.last()
    val data = jsonObject {
        // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", lastMetadata.manifestAnnotations) // TODO lastMetadata?
        addObject("config") { encodeOciDescriptor(configDescriptor) }
        addArray("layers") {
            for (metadata in metadataList) {
                for (layer in metadata.layers) {
                    layer.descriptor?.let {
                        addObject { encodeOciDescriptor(it) }
                    }
                }
            }
        }
        addString("mediaType", MANIFEST_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(MANIFEST_MEDIA_TYPE, data, lastMetadata.manifestDescriptorAnnotations) // TODO lastMetadata?
}

internal fun createIndex(
    manifestDescriptors: List<Pair<Platform, OciDescriptor>>,
    indexAnnotations: SortedMap<String, String>, // TODO
): OciDataDescriptor {
    val data = jsonObject {
        // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", indexAnnotations)
        addArray("manifests") {
            for ((platform, descriptor) in manifestDescriptors) {
                addObject { encodeOciManifestDescriptor(descriptor, platform) }
            }
        }
        addString("mediaType", INDEX_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(INDEX_MEDIA_TYPE, data, sortedMapOf())
}

private fun JsonObjectStringBuilder.encodeOciDescriptor(descriptor: OciDescriptor) {
    // sorted for canonical json: annotations, digest, mediaType, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest.toString())
    addString("mediaType", descriptor.mediaType)
    addNumber("size", descriptor.size)
}

private fun JsonObjectStringBuilder.encodeOciManifestDescriptor(descriptor: OciDescriptor, platform: Platform) {
    // sorted for canonical json: annotations, digest, mediaType, platform, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest.toString())
    addString("mediaType", descriptor.mediaType)
    addObject("platform") {
        // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
        addString("architecture", platform.architecture)
        addString("os", platform.os)
        addArrayIfNotEmpty("os.features", platform.osFeatures)
        addStringIfNotEmpty("os.version", platform.osVersion)
        addStringIfNotEmpty("variant", platform.variant)
    }
    addNumber("size", descriptor.size)
}
