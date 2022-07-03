package io.github.sgtsilvio.gradle.oci

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * @author Silvio Giebl
 */
abstract class OciLayerTask : DefaultTask() {

    // TODO inputs

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val tarFile = project.objects.fileProperty().convention(outputDirectory.file("layer.tar.gz"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("layer.digest"))

    @get:OutputFile
    val diffIdFile = project.objects.fileProperty().convention(outputDirectory.file("layer.diffid"))

    @TaskAction
    protected fun run() {

    }
}