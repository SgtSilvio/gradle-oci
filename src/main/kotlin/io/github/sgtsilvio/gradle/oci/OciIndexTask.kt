package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty

/**
 * @author Silvio Giebl
 */
abstract class OciIndexTask : DefaultTask() {

    @get:Nested
    val manifestDescriptors = project.objects.listProperty<OciManifestDescriptor>()

    @get:Input
    @get:Optional
    val annotations = project.objects.mapProperty<String, String>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("index.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("index.digest"))

    @TaskAction
    protected fun run() {
        val jsonStringBuilder = jsonStringBuilder()
        jsonStringBuilder.addObject { rootObject ->
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            rootObject.addOptionalKeyAndObject("annotations", annotations.orNull)
            rootObject.addKey("manifests").addArray { layersObject ->
                manifestDescriptors.get().forEach { layersObject.addOciManifestDescriptor(it) }
            }
            rootObject.addKey("mediaType").addValue(INDEX_MEDIA_TYPE)
            rootObject.addKey("schemaVersion").addValue(2)
        }
        val jsonBytes = jsonStringBuilder.toString().toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}