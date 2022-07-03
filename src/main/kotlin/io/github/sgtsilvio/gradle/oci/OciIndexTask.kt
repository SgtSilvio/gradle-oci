package io.github.sgtsilvio.gradle.oci

import com.google.cloud.tools.jib.hash.CountingDigestOutputStream
import io.github.sgtsilvio.gradle.oci.internal.addKeyAndObject
import io.github.sgtsilvio.gradle.oci.internal.addOciManifestDescriptor
import io.github.sgtsilvio.gradle.oci.internal.jsonStringBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import java.io.FileOutputStream

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
        val manifestDescriptors = manifestDescriptors.get()
        val annotations = annotations.orNull
        val jsonFile = jsonFile.get().asFile
        val digestFile = digestFile.get().asFile

        val jsonStringBuilder = jsonStringBuilder()
        jsonStringBuilder.addObject { rootObject ->
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            rootObject.addKeyAndObject("annotations", annotations)
            rootObject.addKey("manifests").addArray { layersObject ->
                manifestDescriptors.forEach { manifestDescriptor ->
                    layersObject.addOciManifestDescriptor(manifestDescriptor)
                }
            }
            rootObject.addKey("mediaType").addValue("application/vnd.oci.image.index.v1+json")
            rootObject.addKey("schemaVersion").addValue(2)
        }

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