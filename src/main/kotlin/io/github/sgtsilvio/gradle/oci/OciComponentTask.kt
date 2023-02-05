package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.encodeComponent
import org.gradle.api.DefaultTask
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
    val encodedComponent = project.objects.property<String>().apply { set(component.map(::encodeComponent)) }

    @get:OutputFile
    val componentFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        componentFile.get().asFile.writeText(encodedComponent.get())
    }
}