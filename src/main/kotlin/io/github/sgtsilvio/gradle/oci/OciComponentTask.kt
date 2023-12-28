package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.encodeToJsonString
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

/**
 * @author Silvio Giebl
 */
abstract class OciComponentTask : DefaultTask() {

    @get:Internal
    val component = project.objects.property<OciComponent>()

    @get:Input
    val encodedComponent: Property<String> =
        project.objects.property<String>().value(component.map { it.encodeToJsonString() })

    @get:Internal
    val destinationDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    val classifier = project.objects.property<String>()

    @get:OutputFile
    val componentFile: RegularFileProperty =
        project.objects.fileProperty().convention(destinationDirectory.file(classifier.map { "$it.json" }))

    @TaskAction
    protected fun run() {
        componentFile.get().asFile.writeText(encodedComponent.get())
    }
}
