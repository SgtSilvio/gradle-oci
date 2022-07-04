package io.github.sgtsilvio.gradle.oci

import com.google.cloud.tools.jib.hash.CountingDigestOutputStream
import io.github.sgtsilvio.gradle.oci.internal.addObject
import io.github.sgtsilvio.gradle.oci.internal.addOciDescriptor
import io.github.sgtsilvio.gradle.oci.internal.jsonStringBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.FileOutputStream

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
            annotations.orNull?.takeIf { it.isNotEmpty() }?.let {
                rootObject.addKey("annotations").addObject(it)
            }
            rootObject.addKey("config").addOciDescriptor("application/vnd.oci.image.config.v1+json", configDescriptor)
            rootObject.addKey("layers").addArray { layersObject ->
                layerDescriptors.get().forEach {
                    layersObject.addOciDescriptor("application/vnd.oci.image.layer.v1.tar+gzip", it)
                }
            }
            rootObject.addKey("mediaType").addValue("application/vnd.oci.image.manifest.v1+json")
            rootObject.addKey("schemaVersion").addValue(2)
        }

        val jsonFile = jsonFile.get().asFile
        val digestFile = digestFile.get().asFile

        val digest = FileOutputStream(jsonFile).use { fos ->
            CountingDigestOutputStream(fos).use { dos ->
                dos.write(jsonStringBuilder.toString().toByteArray())
                dos.flush()
                dos.computeDigest().digest.toString()
            }
        }

        digestFile.writeText(digest)
    }
}