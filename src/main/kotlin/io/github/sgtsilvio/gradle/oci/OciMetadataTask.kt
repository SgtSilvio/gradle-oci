package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.component.OciDescriptor
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.internal.*
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

        val data = jsonObject { rootObject ->
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            rootObject.addKey("architecture").addString(platform.architecture)
            rootObject.addKeyAndStringIfNotNull("author", bundles.last().author)
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                configObject.addKeyAndArrayIfNotEmpty("Cmd", arguments)
                configObject.addKeyAndArrayIfNotEmpty("Entrypoint", entryPoint)
                configObject.addKeyAndArrayIfNotEmpty("Env", environment.map { "${it.key}=${it.value}" })
                configObject.addKeyAndObjectIfNotEmpty("ExposedPorts", ports)
                configObject.addKeyAndObjectIfNotEmpty("Labels", annotations)
                configObject.addKeyAndStringIfNotNull("StopSignal", stopSignal)
                configObject.addKeyAndStringIfNotNull("User", user)
                configObject.addKeyAndObjectIfNotEmpty("Volumes", volumes)
                configObject.addKeyAndStringIfNotNull("WorkingDir", workingDirectory)
            }
            rootObject.addKeyAndStringIfNotNull("created", bundles.last().creationTime?.toString())
            rootObject.addKey("history").addArray { historyArray ->
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        historyArray.addObject { historyObject ->
                            // sorted for canonical json: author, comment, created, created_by, empty_layer
                            historyObject.addKeyAndStringIfNotNull("author", layer.author)
                            historyObject.addKeyAndStringIfNotNull("comment", layer.comment)
                            historyObject.addKeyAndStringIfNotNull("created", layer.creationTime?.toString())
                            historyObject.addKeyAndStringIfNotNull("created_by", layer.createdBy)
                            if (layer.descriptor == null) {
                                historyObject.addKey("empty_layer").addBoolean(true)
                            }
                        }
                    }
                }
            }
            rootObject.addKey("os").addString(platform.os)
            rootObject.addKeyAndArrayIfNotEmpty("os.features", platform.osFeatures)
            rootObject.addKeyAndStringIfNotEmpty("os.version", platform.osVersion)
            rootObject.addKey("rootfs").addObject { rootfsObject ->
                // sorted for canonical json: diff_ids, type
                rootfsObject.addKey("diff_ids").addArray { diffIdsArray ->
                    for (bundle in bundles) {
                        for (layer in bundle.layers) {
                            if (layer.descriptor != null) {
                                diffIdsArray.addString(layer.descriptor.diffId)
                            }
                        }
                    }
                }
                rootfsObject.addKey("type").addString("layers")
            }
            rootObject.addKeyAndStringIfNotEmpty("variant", platform.variant)
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

        val data = jsonObject { rootObject ->
            // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
            rootObject.addKeyAndObjectIfNotEmpty("annotations", annotations)
            rootObject.addKey("config").addOciDescriptor(CONFIG_MEDIA_TYPE, configDescriptor)
            rootObject.addKey("layers").addArray { layersObject ->
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        if (layer.descriptor != null) {
                            layersObject.addOciDescriptor(LAYER_MEDIA_TYPE, layer.descriptor)
                        }
                    }
                }
            }
            rootObject.addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addNumber(2)
        }.toByteArray()
        return OciDataDescriptor(data, descriptorAnnotations)
    }

    private fun createIndex(
        manifestDescriptors: List<Pair<Platform, OciDescriptor>>,
        component: OciComponent,
    ): OciDataDescriptor {
        val data = jsonObject { rootObject ->
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            rootObject.addKeyAndObjectIfNotEmpty("annotations", component.indexAnnotations)
            rootObject.addKey("manifests").addArray { layersObject ->
                for ((platform, descriptor) in manifestDescriptors) {
                    layersObject.addOciManifestDescriptor(descriptor, platform)
                }
            }
            rootObject.addKey("mediaType").addString(INDEX_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addNumber(2)
        }.toByteArray()
        return OciDataDescriptor(data, sortedMapOf())
    }

    private fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, descriptor: OciDescriptor) =
        addObject { descriptorObject ->
            // sorted for canonical json: annotations, data, digest, mediaType, size, urls
            descriptorObject.addKeyAndObjectIfNotEmpty("annotations", descriptor.annotations)
//            descriptorObject.addOptionalKeyAndString("data", descriptor.data)
            descriptorObject.addKey("digest").addString(descriptor.digest)
            descriptorObject.addKey("mediaType").addString(mediaType)
            descriptorObject.addKey("size").addNumber(descriptor.size)
//            descriptorObject.addOptionalKeyAndArray("urls", descriptor.urls)
        }

    private fun JsonValueStringBuilder.addOciManifestDescriptor(descriptor: OciDescriptor, platform: Platform) =
        addObject { descriptorObject ->
            // sorted for canonical json: annotations, data, digest, mediaType, size, urls
            descriptorObject.addKeyAndObjectIfNotEmpty("annotations", descriptor.annotations)
//            descriptorObject.addOptionalKeyAndString("data", descriptor.data.orNull)
            descriptorObject.addKey("digest").addString(descriptor.digest)
            descriptorObject.addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
            descriptorObject.addKey("platform").addObject { platformObject ->
                // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
                platformObject.addKey("architecture").addString(platform.architecture)
                platformObject.addKey("os").addString(platform.os)
                platformObject.addKeyAndArrayIfNotEmpty("os.features", platform.osFeatures)
                platformObject.addKeyAndStringIfNotEmpty("os.version", platform.osVersion)
                platformObject.addKeyAndStringIfNotEmpty("variant", platform.variant)
            }
            descriptorObject.addKey("size").addNumber(descriptor.size)
//            descriptorObject.addOptionalKeyAndArray("urls", descriptor.urls)
        }
}