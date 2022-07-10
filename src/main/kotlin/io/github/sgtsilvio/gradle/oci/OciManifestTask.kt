package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance

/**
 * @author Silvio Giebl
 */
abstract class OciManifestTask : DefaultTask() {

    @get:Nested
    val configDescriptor = project.objects.newInstance<OciDescriptor>()

    @get:Nested
    val layerDescriptors = project.objects.listProperty<OciDescriptor>()

    @get:Input
    @get:Optional
    val annotations = project.objects.mapProperty<String, String>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("manifest.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("manifest.digest"))

    @TaskAction
    protected fun run() {
        val jsonStringBuilder = jsonStringBuilder()
        jsonStringBuilder.addObject { rootObject ->
            // sorted for canonical json: annotations, config, layers, mediaType, schemaVersion
            rootObject.addOptionalKeyAndObject("annotations", annotations.orNull)
            rootObject.addKey("config").addOciDescriptor(CONFIG_MEDIA_TYPE, configDescriptor)
            rootObject.addKey("layers").addArray { layersObject ->
                layerDescriptors.get().forEach {
                    layersObject.addOciDescriptor(LAYER_MEDIA_TYPE, it)
                }
            }
            rootObject.addKey("mediaType").addValue(MANIFEST_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addValue(2)
        }
        val jsonBytes = jsonStringBuilder.toString().toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}