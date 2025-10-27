package io.github.sgtsilvio.gradle.oci.metadata

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:Input
    val encodedMetadata = project.objects.property<String>()

    @get:Internal
    val destinationDirectory = project.objects.directoryProperty()

    @get:Internal
    val classifier = project.objects.property<String>()

    @get:OutputFile
    val file = project.objects.fileProperty().convention(destinationDirectory.file(classifier.map { "$it.json" }))

    @TaskAction
    protected fun run() {
        file.get().asFile.writeText(encodedMetadata.get())
    }
}
