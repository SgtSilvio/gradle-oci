package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import java.util.*

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:OutputFile
    val digestToMetadataPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val ociComponentResolver = OciComponentResolver()
        for (file in componentFiles) {
            ociComponentResolver.addComponent(decodeComponent(file.readText()))
        }
        val platforms = ociComponentResolver.resolvePlatforms()
        val configs = mutableListOf<OciDataDescriptor>()
        val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
        for (platform in platforms) {
            val bundlesForPlatform = ociComponentResolver.collectBundlesForPlatform(platform)
            val config = createConfig(platform, bundlesForPlatform)
            configs.add(config)
            val manifest = createManifest(config, bundlesForPlatform)
            manifests.add(Pair(platform, manifest))
        }
        val index = createIndex(manifests, ociComponentResolver.rootComponent)

        digestToMetadataPropertiesFile.get().asFile.bufferedWriter().use { writer ->
            fun writeDataDescriptor(dataDescriptor: OciDataDescriptor) {
                writer.writeProperty(dataDescriptor.digest, String(dataDescriptor.data))
            }
            writeDataDescriptor(index)
            for ((_, manifest) in manifests) {
                writeDataDescriptor(manifest)
            }
            for (config in configs) {
                writeDataDescriptor(config)
            }
        }
    }

    private fun createConfig(platform: Platform, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
        var user: String? = null
        val ports = mutableSetOf<String>()
        val environment = mutableMapOf<String, String>()
        var entryPoint = listOf<String>()
        var arguments = listOf<String>()
        val volumes = mutableSetOf<String>()
        var workingDirectory: String? = null
        var stopSignal: String? = null
        val annotations = mutableMapOf<String, String>()
        val descriptorAnnotations = TreeMap<String, String>()
        for (bundle in bundles) {
            if (bundle.user != null) {
                user = bundle.user
            }
            ports += bundle.ports
            environment += bundle.environment
            if (bundle.command != null) {
                if (bundle.command.entryPoint != null) {
                    entryPoint = bundle.command.entryPoint
                }
                arguments = bundle.command.arguments
            }
            volumes += bundle.volumes
            if (bundle.workingDirectory != null) {
                workingDirectory = bundle.workingDirectory
            }
            if (bundle.stopSignal != null) {
                stopSignal = bundle.stopSignal
            }
            annotations += bundle.configAnnotations
            descriptorAnnotations += bundle.configDescriptorAnnotations
        }

        val data = jsonObject {
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            addKey("architecture").addString(platform.architecture)
            addKeyAndStringIfNotNull("author", bundles.last().author)
            addKey("config").addObject {
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                addKeyAndArrayIfNotEmpty("Cmd", arguments)
                addKeyAndArrayIfNotEmpty("Entrypoint", entryPoint)
                addKeyAndArrayIfNotEmpty("Env", environment.map { "${it.key}=${it.value}" })
                addKeyAndObjectIfNotEmpty("ExposedPorts", ports)
                addKeyAndObjectIfNotEmpty("Labels", annotations)
                addKeyAndStringIfNotNull("StopSignal", stopSignal)
                addKeyAndStringIfNotNull("User", user)
                addKeyAndObjectIfNotEmpty("Volumes", volumes)
                addKeyAndStringIfNotNull("WorkingDir", workingDirectory)
            }
            addKeyAndStringIfNotNull("created", bundles.last().creationTime?.toString())
            addKey("history").addArray {
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        addObject {
                            // sorted for canonical json: author, comment, created, created_by, empty_layer
                            addKeyAndStringIfNotNull("author", layer.author)
                            addKeyAndStringIfNotNull("comment", layer.comment)
                            addKeyAndStringIfNotNull("created", layer.creationTime?.toString())
                            addKeyAndStringIfNotNull("created_by", layer.createdBy)
                            if (layer.descriptor == null) {
                                addKey("empty_layer").addBoolean(true)
                            }
                        }
                    }
                }
            }
            addKey("os").addString(platform.os)
            addKeyAndArrayIfNotEmpty("os.features", platform.osFeatures)
            addKeyAndStringIfNotEmpty("os.version", platform.osVersion)
            addKey("rootfs").addObject {
                // sorted for canonical json: diff_ids, type
                addKey("diff_ids").addArray {
                    for (bundle in bundles) {
                        for (layer in bundle.layers) {
                            if (layer.descriptor != null) {
                                addString(layer.descriptor.diffId)
                            }
                        }
                    }
                }
                addKey("type").addString("layers")
            }
            addKeyAndStringIfNotEmpty("variant", platform.variant)
        }.toByteArray()
        return OciDataDescriptor(data, descriptorAnnotations)
    }

    private fun createManifest(configDescriptor: OciDescriptor, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
        val annotations = mutableMapOf<String, String>()
        val descriptorAnnotations = TreeMap<String, String>()
        for (bundle in bundles) {
            annotations += bundle.manifestAnnotations
            descriptorAnnotations += bundle.manifestDescriptorAnnotations
        }

        val data = jsonObject {
            // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
            addKeyAndObjectIfNotEmpty("annotations", annotations)
            addKey("config").addOciDescriptor(CONFIG_MEDIA_TYPE, configDescriptor)
            addKey("layers").addArray {
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        if (layer.descriptor != null) {
                            addOciDescriptor(LAYER_MEDIA_TYPE, layer.descriptor)
                        }
                    }
                }
            }
            addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
            addKey("schemaVersion").addNumber(2)
        }.toByteArray()
        return OciDataDescriptor(data, descriptorAnnotations)
    }

    private fun createIndex(
        manifestDescriptors: List<Pair<Platform, OciDescriptor>>,
        component: OciComponent,
    ): OciDataDescriptor {
        val data = jsonObject {
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            addKeyAndObjectIfNotEmpty("annotations", component.indexAnnotations)
            addKey("manifests").addArray {
                for ((platform, descriptor) in manifestDescriptors) {
                    addOciManifestDescriptor(descriptor, platform)
                }
            }
            addKey("mediaType").addString(INDEX_MEDIA_TYPE)
            addKey("schemaVersion").addNumber(2)
        }.toByteArray()
        return OciDataDescriptor(data, sortedMapOf())
    }

    private fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, descriptor: OciDescriptor) = addObject {
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        addKeyAndObjectIfNotEmpty("annotations", descriptor.annotations)
//        addOptionalKeyAndString("data", descriptor.data)
        addKey("digest").addString(descriptor.digest)
        addKey("mediaType").addString(mediaType)
        addKey("size").addNumber(descriptor.size)
//        addOptionalKeyAndArray("urls", descriptor.urls)
    }

    private fun JsonValueStringBuilder.addOciManifestDescriptor(descriptor: OciDescriptor, platform: Platform) =
        addObject {
            // sorted for canonical json: annotations, data, digest, mediaType, size, urls
            addKeyAndObjectIfNotEmpty("annotations", descriptor.annotations)
//            addOptionalKeyAndString("data", descriptor.data.orNull)
            addKey("digest").addString(descriptor.digest)
            addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
            addKey("platform").addObject {
                // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
                addKey("architecture").addString(platform.architecture)
                addKey("os").addString(platform.os)
                addKeyAndArrayIfNotEmpty("os.features", platform.osFeatures)
                addKeyAndStringIfNotEmpty("os.version", platform.osVersion)
                addKeyAndStringIfNotEmpty("variant", platform.variant)
            }
            addKey("size").addNumber(descriptor.size)
//            addOptionalKeyAndArray("urls", descriptor.urls)
        }
}