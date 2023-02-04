package io.github.sgtsilvio.gradle.oci.old

import io.github.sgtsilvio.gradle.oci.internal.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.internal.calculateSha256Digest
import io.github.sgtsilvio.gradle.oci.internal.json.addArray
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.addObjectIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
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
abstract class OciIndexTask : DefaultTask() {

    @get:Nested
    val manifestDescriptors = mutableListOf<OciManifestDescriptor>()

    @get:Input
    @get:Optional
    val annotations = project.objects.mapProperty<String, String>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("index.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("index.digest"))

    @get:Inject
    protected abstract val objectFactory: ObjectFactory

    fun addManifestDescriptor(action: Action<in OciManifestDescriptor>) {
        val manifestDescriptor = objectFactory.newInstance<OciManifestDescriptor>()
        manifestDescriptors.add(manifestDescriptor)
        action.execute(manifestDescriptor)
    }

    @TaskAction
    protected fun run() {
        val jsonBytes = jsonObject {
            // sorted for canonical json: annotations, manifests, mediaType, schemaVersion
            addObjectIfNotEmpty("annotations", annotations.orNull)
            addArray("manifests", manifestDescriptors) { addObject { encodeOciManifestDescriptor(it) } }
            addString("mediaType", INDEX_MEDIA_TYPE)
            addNumber("schemaVersion", 2)
        }.toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}