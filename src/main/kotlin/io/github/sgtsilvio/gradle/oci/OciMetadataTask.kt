package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.OciComponentResolver
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*

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
        val configs = mutableListOf<OciComponent.DataDescriptor>()
        val manifests = mutableListOf<Pair<OciComponent.Platform, OciComponent.DataDescriptor>>()
        for (platform in platforms) {
            val bundlesForPlatform = ociComponentResolver.collectBundlesForPlatform(platform)
            val config = createConfig(platform, bundlesForPlatform)
            configs.add(config)
            val manifest = createManifest(config, bundlesForPlatform)
            manifests.add(Pair(platform, manifest))
        }
        val index = createIndex(manifests, ociComponentResolver.rootComponent)

        digestToMetadataPropertiesFile.get().asFile.bufferedWriter().use { writer ->
            fun writeDataDescriptor(dataDescriptor: OciComponent.DataDescriptor) {
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

    private fun createConfig(
        platform: OciComponent.Platform,
        bundles: List<OciComponent.Bundle>,
    ): OciComponent.DataDescriptor {
        var user: String? = null
        val ports = mutableSetOf<String>()
        val environment = mutableMapOf<String, String>()
        var entryPoint = listOf<String>()
        var arguments = listOf<String>()
        val volumes = mutableSetOf<String>()
        var workingDirectory: String? = null
        var stopSignal: String? = null
        val annotations = mutableMapOf<String, String>()
        val descriptorAnnotations = mutableMapOf<String, String>()
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
            rootObject.addOptionalKeyAndString("author", bundles.last().author)
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                configObject.addOptionalKeyAndArray("Cmd", arguments)
                configObject.addOptionalKeyAndArray("Entrypoint", entryPoint)
                configObject.addOptionalKeyAndArray("Env", environment.map { "${it.key}=${it.value}" })
                configObject.addOptionalKeyAndObject("ExposedPorts", ports)
                configObject.addOptionalKeyAndObject("Labels", annotations)
                configObject.addOptionalKeyAndString("StopSignal", stopSignal)
                configObject.addOptionalKeyAndString("User", user)
                configObject.addOptionalKeyAndObject("Volumes", volumes)
                configObject.addOptionalKeyAndString("WorkingDir", workingDirectory)
            }
            rootObject.addOptionalKeyAndString("created", bundles.last().creationTime?.toString())
            rootObject.addKey("history").addArray { historyArray ->
                for (bundle in bundles) {
                    for (layer in bundle.layers) {
                        historyArray.addObject { historyObject ->
                            // sorted for canonical json: author, comment, created, created_by, empty_layer
                            historyObject.addOptionalKeyAndString("author", layer.author)
                            historyObject.addOptionalKeyAndString("comment", layer.comment)
                            historyObject.addOptionalKeyAndString("created", layer.creationTime?.toString())
                            historyObject.addOptionalKeyAndString("created_by", layer.createdBy)
                            if (layer.descriptor == null) {
                                historyObject.addKey("empty_layer").addBoolean(true)
                            }
                        }
                    }
                }
            }
            rootObject.addKey("os").addString(platform.os)
            rootObject.addOptionalKeyAndArray("os.features", platform.osFeatures)
            rootObject.addOptionalKeyAndString("os.version", platform.osVersion)
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
            rootObject.addOptionalKeyAndString("variant", platform.variant)
        }.toByteArray()
        return OciComponent.DataDescriptor(data, descriptorAnnotations)
    }

    private fun createManifest(
        configDescriptor: OciComponent.Descriptor,
        bundles: List<OciComponent.Bundle>,
    ): OciComponent.DataDescriptor {
        val annotations = mutableMapOf<String, String>()
        val descriptorAnnotations = mutableMapOf<String, String>()
        for (bundle in bundles) {
            annotations += bundle.manifestAnnotations
            descriptorAnnotations += bundle.manifestDescriptorAnnotations
        }

        val data = jsonObject { rootObject ->
            // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
            rootObject.addOptionalKeyAndObject("annotations", annotations)
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
        return OciComponent.DataDescriptor(data, descriptorAnnotations)
    }

    private fun createIndex(
        manifestDescriptors: List<Pair<OciComponent.Platform, OciComponent.Descriptor>>,
        component: OciComponent,
    ): OciComponent.DataDescriptor {
        val data = jsonObject { rootObject ->
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            rootObject.addOptionalKeyAndObject("annotations", component.indexAnnotations)
            rootObject.addKey("manifests").addArray { layersObject ->
                for ((platform, descriptor) in manifestDescriptors) {
                    layersObject.addOciManifestDescriptor(descriptor, platform)
                }
            }
            rootObject.addKey("mediaType").addString(INDEX_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addNumber(2)
        }.toByteArray()
        return OciComponent.DataDescriptor(data, mapOf())
    }

    private fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, descriptor: OciComponent.Descriptor) =
        addObject { descriptorObject ->
            // sorted for canonical json: annotations, data, digest, mediaType, size, urls
            descriptorObject.addOptionalKeyAndObject("annotations", descriptor.annotations)
//            descriptorObject.addOptionalKeyAndString("data", descriptor.data)
            descriptorObject.addKey("digest").addString(descriptor.digest)
            descriptorObject.addKey("mediaType").addString(mediaType)
            descriptorObject.addKey("size").addNumber(descriptor.size)
//            descriptorObject.addOptionalKeyAndArray("urls", descriptor.urls)
        }

    private fun JsonValueStringBuilder.addOciManifestDescriptor(
        descriptor: OciComponent.Descriptor,
        platform: OciComponent.Platform,
    ) = addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addOptionalKeyAndObject("annotations", descriptor.annotations)
//            descriptorObject.addOptionalKeyAndString("data", descriptor.data.orNull)
        descriptorObject.addKey("digest").addString(descriptor.digest)
        descriptorObject.addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
        descriptorObject.addKey("platform").addObject { platformObject ->
            // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
            platformObject.addKey("architecture").addString(platform.architecture)
            platformObject.addKey("os").addString(platform.os)
            platformObject.addOptionalKeyAndArray("os.features", platform.osFeatures)
            platformObject.addOptionalKeyAndString("os.version", platform.osVersion)
            platformObject.addOptionalKeyAndString("variant", platform.variant)
        }
        descriptorObject.addKey("size").addNumber(descriptor.size)
//            descriptorObject.addOptionalKeyAndArray("urls", descriptor.urls)
    }
}