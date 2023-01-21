package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import io.github.sgtsilvio.gradle.oci.internal.writeProperty
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty

/**
 * @author Silvio Giebl
 */
abstract class OciLayerDigestsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:Input
    val layerPaths = project.objects.listProperty<String>()

    @get:OutputFile
    val digestToLayerPathPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val digestToLayerPath = LinkedHashMap<String, String>()
        val layerPaths = layerPaths.get()
        var i = 0
        for (componentFile in componentFiles) {
            val component = decodeComponent(componentFile.readText())
            val componentDigests = HashSet<String>()
            iterateLayers(component) { layer ->
                if (layer.descriptor != null) {
                    val digest = layer.descriptor.digest
                    if (componentDigests.add(digest)) {
                        if (i >= layerPaths.size) {
                            throw IllegalStateException("componentFiles and layerPaths inputs do not match: number of unique digests (>=$i) differs from number of layer paths (${layerPaths.size})")
                        }
                        digestToLayerPath[digest] = layerPaths[i]
                        i++
                    }
                }
            }
        }
        if (i != layerPaths.size) {
            throw IllegalStateException("componentFiles and layerPaths inputs do not match: number of unique digests ($i) differs from number of layer paths (${layerPaths.size})")
        }
        digestToLayerPathPropertiesFile.get().asFile.bufferedWriter().use { writer ->
            for ((digest, layerPath) in digestToLayerPath) {
                writer.writeProperty(digest, layerPath)
            }
        }
    }

    private inline fun iterateLayers(component: OciComponent, action: (OciComponent.Bundle.Layer) -> Unit) {
        when (component.bundleOrPlatformBundles) {
            is OciComponent.Bundle -> component.bundleOrPlatformBundles.layers.forEach(action)
            is OciComponent.PlatformBundles -> component.bundleOrPlatformBundles.map.forEach { (_, bundle) ->
                bundle.layers.forEach(action)
            }
        }
    }
}