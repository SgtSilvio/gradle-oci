package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.OciDataDescriptor
import io.github.sgtsilvio.gradle.oci.component.OciDescriptor
import io.github.sgtsilvio.gradle.oci.internal.CONFIG_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.internal.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.internal.LAYER_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.internal.MANIFEST_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.util.*

/**
 * @author Silvio Giebl
 */
fun createConfig(platform: Platform, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
    var user: String? = null
    val ports = TreeSet<String>()
    val environment = TreeMap<String, String>()
    var entryPoint = listOf<String>()
    var arguments = listOf<String>()
    val volumes = TreeSet<String>()
    var workingDirectory: String? = null
    var stopSignal: String? = null
    val annotations = TreeMap<String, String>()
    val descriptorAnnotations = TreeMap<String, String>()
    for (bundle in bundles) {
        bundle.user?.let { user = it }
        ports.addAll(bundle.ports)
        environment += bundle.environment
        bundle.command?.let { command ->
            command.entryPoint?.let { entryPoint = it }
            arguments = command.arguments
        }
        volumes.addAll(bundle.volumes)
        bundle.workingDirectory?.let { workingDirectory = it }
        bundle.stopSignal?.let { stopSignal = it }
        annotations += bundle.configAnnotations
        descriptorAnnotations += bundle.configDescriptorAnnotations
    }

    val data = jsonObject {
        // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
        addString("architecture", platform.architecture)
        addStringIfNotNull("author", bundles.last().author)
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
        addStringIfNotNull("created", bundles.last().creationTime?.toString())
        addArray("history") {
            for (bundle in bundles) {
                for (layer in bundle.layers) {
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
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        layer.descriptor?.let {
                            addString(it.diffId)
                        }
                    }
                }
            }
            addString("type", "layers")
        }
        addStringIfNotEmpty("variant", platform.variant)
    }.toByteArray()
    return OciDataDescriptor(data, descriptorAnnotations)
}

fun createManifest(configDescriptor: OciDescriptor, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
    val annotations = TreeMap<String, String>()
    val descriptorAnnotations = TreeMap<String, String>()
    for (bundle in bundles) {
        annotations += bundle.manifestAnnotations
        descriptorAnnotations += bundle.manifestDescriptorAnnotations
    }

    val data = jsonObject {
        // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", annotations)
        addObject("config") { encodeOciDescriptor(CONFIG_MEDIA_TYPE, configDescriptor) }
        addArray("layers") {
            for (bundle in bundles) {
                for (layer in bundle.layers) {
                    layer.descriptor?.let {
                        addObject { encodeOciDescriptor(LAYER_MEDIA_TYPE, it) }
                    }
                }
            }
        }
        addString("mediaType", MANIFEST_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(data, descriptorAnnotations)
}

fun createIndex(
    manifestDescriptors: List<Pair<Platform, OciDescriptor>>,
    component: OciComponent,
): OciDataDescriptor {
    val data = jsonObject {
        // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
        addObjectIfNotEmpty("annotations", component.indexAnnotations)
        addArray("manifests") {
            for ((platform, descriptor) in manifestDescriptors) {
                addObject { encodeOciManifestDescriptor(descriptor, platform) }
            }
        }
        addString("mediaType", INDEX_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }.toByteArray()
    return OciDataDescriptor(data, sortedMapOf())
}

private fun JsonObjectStringBuilder.encodeOciDescriptor(mediaType: String, descriptor: OciDescriptor) {
    // sorted for canonical json: annotations, digest, mediaType, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest)
    addString("mediaType", mediaType)
    addNumber("size", descriptor.size)
}

private fun JsonObjectStringBuilder.encodeOciManifestDescriptor(descriptor: OciDescriptor, platform: Platform) {
    // sorted for canonical json: annotations, digest, mediaType, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest)
    addString("mediaType", MANIFEST_MEDIA_TYPE)
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