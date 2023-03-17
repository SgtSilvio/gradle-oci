package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.Capability
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.OciComponentResolver
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
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

        val componentsAndLayerDigests = mutableListOf<Pair<OciComponent, Set<String>>>()
        val digestToLayer = hashMapOf<String, File>()
        val iterator = ociFiles.iterator()
        while (iterator.hasNext()) {
            val componentFile = iterator.next()
            val component = decodeComponent(componentFile.readText()) // TODO check if fails
            val componentLayerDigests = HashSet<String>()
            iterateLayers(component) { layer -> // TODO double inline
                layer.descriptor?.let {
                    val digest = it.digest
                    if (componentLayerDigests.add(digest)) {
                        if (!iterator.hasNext()) {
                            throw IllegalStateException() // TODO message
                        }
                        val layerFile = iterator.next()
                        val prevLayerFile = digestToLayer.putIfAbsent(digest, layerFile)
                        if (prevLayerFile != null) {
                            if (layerFile.length() != prevLayerFile.length()) {
                                throw IllegalStateException("hash collision") // TODO message
                            } else {
                                // TODO warn
                            }
                        }
                    }
                }
            }
            componentsAndLayerDigests += Pair(component, componentLayerDigests)
        }

        val blobsDirectory = registryDataDirectory.resolve("blobs")
        for ((digest, layer) in digestToLayer) {
            val (alg, hex) = digest.split(':', limit = 2)
            val blobDirectory = blobsDirectory.resolve(alg).resolve(hex.substring(0, 2)).resolve(hex)
            Files.createDirectories(blobDirectory)
            Files.createLink(blobDirectory.resolve("data"), layer.toPath())
        }

        val componentResolver = OciComponentResolver()
        for ((component, _) in componentsAndLayerDigests) {
            componentResolver.addComponent(component)
        }
        for (rootCapability in rootCapabilities.get()) {
            val componentResolverRoot = componentResolver.Root(rootCapability)
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