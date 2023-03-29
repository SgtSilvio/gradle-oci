package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.mapping.OciImageNameCapabilityMapper
import io.github.sgtsilvio.gradle.oci.mapping.OciImageNameCapabilityMapping
import io.github.sgtsilvio.gradle.oci.mapping.createCapabilityMapper
import io.github.sgtsilvio.gradle.oci.metadata.createConfig
import io.github.sgtsilvio.gradle.oci.metadata.createIndex
import io.github.sgtsilvio.gradle.oci.metadata.createManifest
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * @author Silvio Giebl
 */
abstract class OciRegistryDataTask : DefaultTask() {

    interface Images {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NONE)
        val ociFiles: ConfigurableFileCollection

        @get:Input
        val rootCapabilities: SetProperty<Capability>
    }

    @get:Nested
    val imagesList = project.objects.listProperty<Images>()

    @get:Nested
    val imageNameMapping = project.objects.newInstance<OciImageNameCapabilityMapping>()

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    protected fun run() {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()
        val imageNameMapper = imageNameMapping.createCapabilityMapper()
        for (images in imagesList.get()) {
            images.writeTo(registryDataDirectory, imageNameMapper)
        }
    }

    private fun Images.writeTo(registryDataDirectory: Path, imageNameMapper: OciImageNameCapabilityMapper) {
        val componentAndDigestToLayerPairs = findComponents(ociFiles)

        val blobsDirectory: Path = registryDataDirectory.resolve("blobs")
        blobsDirectory.writeLayers(componentAndDigestToLayerPairs)

        val repositoriesDirectory: Path = registryDataDirectory.resolve("repositories")
        val componentResolver = OciComponentResolver()
        for ((component, _) in componentAndDigestToLayerPairs) {
            componentResolver.addComponent(component)
        }
        val rootCapabilities: Set<Capability> = rootCapabilities.get()
        for (rootCapability in rootCapabilities) {
            val resolvedComponent = componentResolver.resolve(rootCapability)
            val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
            val imageDigests = hashSetOf<String>()
            for (platform in resolvedComponent.platforms) {
                val bundlesForPlatform = resolvedComponent.collectBundlesForPlatform(platform)
                for (bundle in bundlesForPlatform) {
                    for (layer in bundle.layers) {
                        layer.descriptor?.let {
                            imageDigests += it.digest
                        }
                    }
                }
                val config = createConfig(platform, bundlesForPlatform)
                blobsDirectory.writeDigestData(config)
                imageDigests += config.digest
                val manifest = createManifest(config, bundlesForPlatform)
                blobsDirectory.writeDigestData(manifest)
                manifests += Pair(platform, manifest)
                imageDigests += manifest.digest
            }
            val index = createIndex(manifests, resolvedComponent.component)
            blobsDirectory.writeDigestData(index)
            val indexDigest = index.digest

            val imageNames = imageNameMapper.map(
                resolvedComponent.component.capabilities,
                resolvedComponent.collectCapabilities(),
            )
            for (imageName in imageNames) {
                val repositoryDirectory: Path = Files.createDirectories(
                    repositoriesDirectory.resolve(imageName.namespace).resolve(imageName.name)
                )
                val layersDirectory: Path = Files.createDirectories(repositoryDirectory.resolve("_layers"))
                for (imageDigest in imageDigests) {
                    layersDirectory.writeDigestLink(imageDigest)
                }
                val manifestsDirectory: Path = Files.createDirectories(repositoryDirectory.resolve("_manifests"))
                Files.createDirectories(manifestsDirectory.resolve("revisions")).writeDigestLink(indexDigest)
                val tagDirectory: Path =
                    Files.createDirectories(manifestsDirectory.resolve("tags").resolve(imageName.tag))
                tagDirectory.writeTagLink(indexDigest)
                Files.createDirectories(tagDirectory.resolve("index")).writeDigestLink(indexDigest)
            }
        }
    }

    private fun findComponents(ociFiles: Iterable<File>): MutableList<Pair<OciComponent, Map<String, File>>> {
        val componentAndDigestToLayerPairs = mutableListOf<Pair<OciComponent, Map<String, File>>>()
        val iterator = ociFiles.iterator()
        while (iterator.hasNext()) {
            val componentFile = iterator.next()
            val component = decodeComponent(componentFile.readText())
            val digestToLayer = hashMapOf<String, File>()
            for (layer in component.allLayers) {
                layer.descriptor?.let {
                    val digest = it.digest
                    if (digest !in digestToLayer) {
                        if (!iterator.hasNext()) {
                            throw IllegalStateException("ociFiles are missing layers referenced in components")
                        }
                        digestToLayer[digest] = iterator.next()
                    }
                }
            }
            componentAndDigestToLayerPairs += Pair(component, digestToLayer)
        }
        return componentAndDigestToLayerPairs
    }

    private fun Path.writeLayers(componentAndDigestToLayerPairs: MutableList<Pair<OciComponent, Map<String, File>>>) {
        for ((_, digestToLayerPerComponent) in componentAndDigestToLayerPairs) {
            for ((digest, layer) in digestToLayerPerComponent) {
                val digestDataFile = resolveDigestDataFile(digest)
                try {
                    Files.createLink(digestDataFile, layer.toPath())
                } catch (e: FileAlreadyExistsException) {
                    if (FileUtils.contentEquals(digestDataFile.toFile(), layer)) {
                        logger.warn("the same layer ($digest) should not be provided by multiple components")
                    } else {
                        throw IllegalStateException("hash collision for digest $digest: expected file contents of $digestDataFile and $layer to be the same")
                    }
                }
            }
        }
    }

    private fun Path.resolveDigestDataFile(digest: String): Path {
        val (alg, hex) = digest.split(':', limit = 2)
        return Files.createDirectories(resolve(alg).resolve(hex.substring(0, 2)).resolve(hex)).resolve("data")
    }

    private fun Path.writeDigestData(dataDescriptor: OciDataDescriptor) {
        val digestDataFile = resolveDigestDataFile(dataDescriptor.digest)
        try {
            Files.write(digestDataFile, dataDescriptor.data, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!dataDescriptor.data.contentEquals(Files.readAllBytes(digestDataFile))) {
                throw IllegalStateException("hash collision for digest ${dataDescriptor.digest}: expected file content of $digestDataFile to be the same as ${dataDescriptor.data.contentToString()}")
            }
        }
    }

    private fun Path.writeDigestLink(digest: String) {
        val (alg, hex) = digest.split(':', limit = 2)
        Files.write(Files.createDirectories(resolve(alg).resolve(hex)).resolve("link"), digest.toByteArray())
    }

    private fun Path.writeTagLink(digest: String) {
        val tagLinkFile = Files.createDirectories(resolve("current")).resolve("link")
        val digestBytes = digest.toByteArray()
        try {
            Files.write(tagLinkFile, digestBytes)
        } catch (e: FileAlreadyExistsException) {
            if (!digestBytes.contentEquals(Files.readAllBytes(tagLinkFile))) {
                throw IllegalStateException("tried to link the same image name/tag to different images")
            }
        }
    }

    private val OciComponent.allLayers
        get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
            is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
            is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
        }
}

private fun Path.ensureEmptyDirectory(): Path {
    if (!toFile().deleteRecursively()) {
        throw IOException("$this could not be deleted")
    }
    return Files.createDirectories(this)
}
