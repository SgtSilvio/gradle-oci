package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.metadata.createConfig
import io.github.sgtsilvio.gradle.oci.metadata.createIndex
import io.github.sgtsilvio.gradle.oci.metadata.createManifest
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.setProperty
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * @author Silvio Giebl
 */
abstract class OciRegistryDataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val ociFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:Input
    val rootCapabilities = project.objects.setProperty<Capability>()

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    protected fun run() {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()

        val componentAndDigestToLayerPairs = mutableListOf<Pair<OciComponent, Map<String, File>>>()
        val iterator = ociFiles.iterator()
        while (iterator.hasNext()) {
            val componentFile = iterator.next()
            val component = decodeComponent(componentFile.readText()) // TODO check if fails
            val digestToLayer = hashMapOf<String, File>()
            iterateLayers(component) { layer -> // TODO double inline
                layer.descriptor?.let {
                    val digest = it.digest
                    if (digest !in digestToLayer) {
                        if (!iterator.hasNext()) {
                            throw IllegalStateException() // TODO message
                        }
                        digestToLayer[digest] = iterator.next()
                    }
                }
            }
            componentAndDigestToLayerPairs += Pair(component, digestToLayer)
        }

        val digestToLayer = hashMapOf<String, File>()
        for ((_, digestToLayerPerComponent) in componentAndDigestToLayerPairs) {
            for ((digest, layer) in digestToLayerPerComponent) {
                val prevLayer = digestToLayer.putIfAbsent(digest, layer)
                if (prevLayer != null) {
                    if (layer.length() != prevLayer.length()) {
                        throw IllegalStateException("hash collision") // TODO message
                    } else {
                        // TODO warn that same layer should not be provided by different components
                    }
                }
            }
        }

        val blobsDirectory = registryDataDirectory.resolve("blobs")
        for ((digest, layer) in digestToLayer) {
            Files.createLink(blobsDirectory.resolveDigestDataFile(digest), layer.toPath())
        }

        val repositoriesDirectory = registryDataDirectory.resolve("repositories")
        val componentResolver = OciComponentResolver()
        for ((component, _) in componentAndDigestToLayerPairs) {
            componentResolver.addComponent(component)
        }
        for (rootCapability in rootCapabilities.get()) {
            val resolvedComponent = componentResolver.resolve(rootCapability)
            val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
            val imageDigests = mutableSetOf<String>()
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

            resolvedComponent.component.capabilities.forEach { versionedCapability ->
                val imageNamespace = groupToImageNamespace(versionedCapability.capability.group)
                val repositoryDirectory =
                    repositoriesDirectory.resolve(imageNamespace).resolve(versionedCapability.capability.name)
                Files.createDirectories(repositoryDirectory)
                val layersDirectory = repositoryDirectory.resolve("_layers")
                Files.createDirectories(layersDirectory)
                for (imageDigest in imageDigests) {
                    layersDirectory.writeDigestLink(imageDigest)
                }
                val manifestsDirectory = repositoryDirectory.resolve("_manifests")
                Files.createDirectories(manifestsDirectory)
                val revisionsDirectory = manifestsDirectory.resolve("revisions")
                Files.createDirectories(revisionsDirectory)
                revisionsDirectory.writeDigestLink(indexDigest)
                val tagDirectory = manifestsDirectory.resolve("tags").resolve(versionedCapability.version)
                Files.createDirectories(tagDirectory)
                val tagCurrentDirectory = tagDirectory.resolve("current")
                Files.createDirectories(tagCurrentDirectory)
                Files.write(tagCurrentDirectory.resolve("link"), indexDigest.toByteArray())
                val tagIndexDirectory = tagDirectory.resolve("index")
                Files.createDirectories(tagIndexDirectory)
                tagIndexDirectory.writeDigestLink(indexDigest)
            }
        }
    }
}

private fun Path.ensureEmptyDirectory(): Path {
    if (!toFile().deleteRecursively()) {
        throw IOException("$this could not be deleted")
    }
    return Files.createDirectories(this)
}

private inline fun iterateLayers(component: OciComponent, action: (OciComponent.Bundle.Layer) -> Unit) {
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> bundleOrPlatformBundles.layers.forEach(action)
        is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.forEach { bundle ->
            bundle.layers.forEach(action)
        }
    }
}

private fun groupToImageNamespace(group: String): String {
    val tldEndIndex = group.indexOf('.')
    return if (tldEndIndex == -1) {
        group
    } else {
        group.substring(tldEndIndex + 1).replace('.', '/')
    }
}

private fun Path.resolveDigestLinkFile(digest: String): Path {
    val (alg, hex) = digest.split(':', limit = 2)
    return Files.createDirectories(resolve(alg).resolve(hex)).resolve("link")
}

private fun Path.resolveDigestDataFile(digest: String): Path {
    val (alg, hex) = digest.split(':', limit = 2)
    return Files.createDirectories(resolve(alg).resolve(hex.substring(0, 2)).resolve(hex)).resolve("data")
}

private fun Path.writeDigestLink(digest: String) {
    Files.write(resolveDigestLinkFile(digest), digest.toByteArray())
}

private fun Path.writeDigestData(dataDescriptor: OciDataDescriptor) {
    Files.write(resolveDigestDataFile(dataDescriptor.digest), dataDescriptor.data)
}
