package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.encodeComponent
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

/**
 * @author Silvio Giebl
 */
abstract class OciComponentTask : DefaultTask() {

    @get:Input
    val component = project.objects.property<OciComponent>()

    @get:OutputFile
    val componentFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val encodedComponent = encodeComponent(component.get())
        componentFile.get().asFile.bufferedWriter().use { writer ->
            encodedComponent.write(writer)
        }
    }
}