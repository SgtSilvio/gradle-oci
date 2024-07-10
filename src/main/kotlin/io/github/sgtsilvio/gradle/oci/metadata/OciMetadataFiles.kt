package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.OciImage
import io.github.sgtsilvio.gradle.oci.OciVariant
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.util.*

internal fun createConfig(platform: Platform, variants: List<OciVariant>): OciDataDescriptor {
    var user: String? = null
    val ports = TreeSet<String>()
    val environment = TreeMap<String, String>()
    var entryPoint = emptyList<String>()
    var arguments = emptyList<String>()
    val volumes = TreeSet<String>()
    var workingDirectory: String? = null
    var stopSignal: String? = null
    val annotations = TreeMap<String, String>()
    for (variant in variants) {
        val metadata = variant.metadata
        metadata.user?.let { user = it }
        ports.addAll(metadata.ports)
        environment += metadata.environment
        metadata.entryPoint?.let {
            entryPoint = it
            arguments = emptyList()
        }
        metadata.arguments?.let { arguments = it }
        volumes.addAll(metadata.volumes)
        metadata.workingDirectory?.let { workingDirectory = it }
        metadata.stopSignal?.let { stopSignal = it }
        annotations += metadata.configAnnotations
    }
    val lastVariantMetadata = variants.last().metadata
    val data = jsonObject {
        // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
        addString("architecture", platform.architecture)
        addStringIfNotNull("author", lastVariantMetadata.author)
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
        addStringIfNotNull("created", lastVariantMetadata.creationTime?.toString())
        addArray("history") {
            for (variant in variants) {
                for (layer in variant.metadata.layers) {
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
                for (variant in variants) {
                    for (layer in variant.layers) {
                        addString(layer.descriptor.diffId.toString())
                    }
                }
            }
            addString("type", "layers")
        }
        addStringIfNotEmpty("variant", platform.variant)
    }.toByteArray()
    return OciDataDescriptor(CONFIG_MEDIA_TYPE, data, lastVariantMetadata.configDescriptorAnnotations) // TODO lastVariantMetadata?
}

internal fun createManifest(configDescriptor: OciDescriptor, variants: List<OciVariant>): OciDataDescriptor {
    val lastVariantMetadata = variants.last().metadata
    val data = jsonObject {
        // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", lastVariantMetadata.manifestAnnotations) // TODO lastVariantMetadata?
        addObject("config") { encodeOciDescriptor(configDescriptor) }
        addArray("layers") {
            for (variant in variants) {
                for (layer in variant.layers) {
                    addObject { encodeOciDescriptor(layer.descriptor) }
                }
            }
        }
        addString("mediaType", MANIFEST_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(MANIFEST_MEDIA_TYPE, data, lastVariantMetadata.manifestDescriptorAnnotations) // TODO lastVariantMetadata?
}

internal fun createIndex(platformToImage: Map<Platform, OciImage>): OciDataDescriptor {
    val indexAnnotations = TreeMap<String, String>()
    val images = platformToImage.values
    if (images.isNotEmpty()) {
        for (indexAnnotation in images.first().variants.last().metadata.indexAnnotations) {
            if (images.all { indexAnnotation in it.variants.last().metadata.indexAnnotations.entries }) {
                indexAnnotations[indexAnnotation.key] = indexAnnotation.value
            }
        }
    }
    val data = jsonObject {
        // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", indexAnnotations)
        addArray("manifests") {
            for ((platform, image) in platformToImage) {
                addObject { encodeOciManifestDescriptor(image.manifest, platform) }
            }
        }
        addString("mediaType", INDEX_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(INDEX_MEDIA_TYPE, data, TreeMap())
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
