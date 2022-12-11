package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import io.github.sgtsilvio.gradle.oci.internal.escapePropertiesKey
import io.github.sgtsilvio.gradle.oci.internal.escapePropertiesValue
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*

/**
 * @author Silvio Giebl
 */
abstract class OciLayerDigestsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val layerFiles = project.objects.fileCollection()

    @get:OutputFile
    val digestToLayerPathPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val digestToLayerPath = mutableMapOf<String, String>()
        val layerFiles = layerFiles.files.toList()
        var i = 0
        for (file in componentFiles) {
            val component = decodeComponent(file.readText())
            iterateLayers(component) { layer ->
                if (layer.descriptor != null) {
                    digestToLayerPath[layer.descriptor.digest] = layerFiles[i].absolutePath
                    i++
                }
            }
        }
        digestToLayerPathPropertiesFile.get().asFile.bufferedWriter().use { writer ->
            for ((digest, layerPath) in digestToLayerPath) {
                writer.write(digest.escapePropertiesKey())
                writer.write('='.toInt())
                writer.write(layerPath.escapePropertiesValue())
                writer.write('\n'.toInt())
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