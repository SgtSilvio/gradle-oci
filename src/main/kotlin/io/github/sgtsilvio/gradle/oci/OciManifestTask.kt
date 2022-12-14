package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciManifestTask : DefaultTask() {

    @get:Nested
    val configDescriptor = project.objects.newInstance<OciDescriptor>()

    @get:Nested
    val layerDescriptors = mutableListOf<OciDescriptor>()

    @get:Input
    @get:Optional
    val annotations = project.objects.mapProperty<String, String>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("manifest.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("manifest.digest"))

    @get:Inject
    protected abstract val objectFactory: ObjectFactory

    fun configDescriptor(action: Action<in OciDescriptor>) = action.execute(configDescriptor)

    fun addLayerDescriptor(action: Action<in OciDescriptor>) {
        val layerDescriptor = objectFactory.newInstance<OciDescriptor>()
        layerDescriptors.add(layerDescriptor)
        action.execute(layerDescriptor)
    }

    @TaskAction
    protected fun run() {
        val jsonBytes = jsonObject { rootObject ->
            // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
            rootObject.addOptionalKeyAndObject("annotations", annotations.orNull)
            rootObject.addKey("config").addOciDescriptor(CONFIG_MEDIA_TYPE, configDescriptor)
            rootObject.addKey("layers").addArray { layersObject ->
                for (layerDescriptor in layerDescriptors) {
                    layersObject.addOciDescriptor(LAYER_MEDIA_TYPE, layerDescriptor)
                }
            }
            rootObject.addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addNumber(2)
        }.toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}