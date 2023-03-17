package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.util.*

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    val digestToMetadataPropertiesFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val componentResolverRoot = createComponentResolverRoot()
        val platforms = componentResolverRoot.resolvePlatforms()
        val configs = mutableListOf<OciDataDescriptor>()
        val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
        for (platform in platforms) {
            val bundlesForPlatform = componentResolverRoot.collectBundlesForPlatform(platform)
            val config = createConfig(platform, bundlesForPlatform)
            configs.add(config)
            val manifest = createManifest(config, bundlesForPlatform)
            manifests.add(Pair(platform, manifest))
        }
        val index = createIndex(manifests, componentResolverRoot.component)

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

    private fun createComponentResolverRoot(): OciComponentResolver.Root {
        val componentResolver = OciComponentResolver()
        var rootComponent: OciComponent? = null
        for (file in componentFiles) {
            val component = decodeComponent(file.readText())
            if (rootComponent == null) {
                rootComponent = component
            }
            componentResolver.addComponent(component)
        }
        if (rootComponent == null) {
            throw IllegalStateException("at least one component is required")
        }
        return componentResolver.Root(rootComponent)
    }

    private fun createConfig(platform: Platform, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
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
            if (bundle.user != null) {
                user = bundle.user
            }
            ports.addAll(bundle.ports)
            environment += bundle.environment
            if (bundle.command != null) {
                if (bundle.command.entryPoint != null) {
                    entryPoint = bundle.command.entryPoint
                }
                arguments = bundle.command.arguments
            }
            volumes.addAll(bundle.volumes)
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
                            if (layer.descriptor != null) {
                                addString(layer.descriptor.diffId)
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

    private fun createManifest(configDescriptor: OciDescriptor, bundles: List<OciComponent.Bundle>): OciDataDescriptor {
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
                        if (layer.descriptor != null) {
                            addObject { encodeOciDescriptor(LAYER_MEDIA_TYPE, layer.descriptor) }
                        }
                    }
                }
            }
            addString("mediaType", MANIFEST_MEDIA_TYPE)
            addNumber("schemaVersion", 2)
        }.toByteArray()
        return OciDataDescriptor(data, descriptorAnnotations)
    }

    private fun createIndex(
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
}