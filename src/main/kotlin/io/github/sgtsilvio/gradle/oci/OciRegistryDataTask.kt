package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
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
    val rootCapabilities = project.objects.listProperty<Capability>()

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    protected fun run() {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()

        val componentsAndDigestToLayers = mutableListOf<Pair<OciComponent, Map<String, File>>>()
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
            componentsAndDigestToLayers += Pair(component, digestToLayer)
        }

        val digestToLayer = hashMapOf<String, File>()
        for ((_, componentDigestToLayer) in componentsAndDigestToLayers) {
            for ((digest, layer) in componentDigestToLayer) {
                val prevLayer = digestToLayer.putIfAbsent(digest, layer)
                if ((prevLayer != null) && (layer != prevLayer)) {
                    if (layer.length() != prevLayer.length()) {
                        throw IllegalStateException("hash collision") // TODO message
                    } else {
                        // TODO warn
                    }
                }
            }
        }

        val blobsDirectory = registryDataDirectory.resolve("blobs")
        for ((digest, layer) in digestToLayer) {
            val (alg, hex) = digest.split(':', limit = 2)
            val blobDirectory = blobsDirectory.resolve(alg).resolve(hex.substring(0, 2)).resolve(hex)
            Files.createDirectories(blobDirectory)
            Files.createLink(blobDirectory.resolve("data"), layer.toPath())
        }

        val repositoriesDirectory = registryDataDirectory.resolve("repositories")
        val componentResolver = OciComponentResolver()
        for ((component, _) in componentsAndDigestToLayers) {
            componentResolver.addComponent(component)
        }
        for (rootCapability in rootCapabilities.get()) {
            val componentResolverRoot = componentResolver.Root(rootCapability)
            componentResolverRoot.component.capabilities.forEach { versionedCapability ->
                val imageNamespace = groupToImageNamespace(versionedCapability.capability.group)
                val repositoryDirectory =
                    repositoriesDirectory.resolve(imageNamespace).resolve(versionedCapability.capability.name)
                Files.createDirectories(repositoryDirectory)
                val layersDirectory = repositoryDirectory.resolve("_layers")
                Files.createDirectories(layersDirectory)
                val manifestsDirectory = repositoryDirectory.resolve("_manifests")
                Files.createDirectories(manifestsDirectory)
                val revisionsDirectory = manifestsDirectory.resolve("revisions")
                Files.createDirectories(revisionsDirectory)
                val tagDirectory = manifestsDirectory.resolve("tags").resolve(versionedCapability.version)
                Files.createDirectories(tagDirectory)
                val tagCurrentDirectory = tagDirectory.resolve("current")
                Files.createDirectories(tagCurrentDirectory)
                val tagIndexDirectory = tagDirectory.resolve("index")
                Files.createDirectories(tagIndexDirectory)
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