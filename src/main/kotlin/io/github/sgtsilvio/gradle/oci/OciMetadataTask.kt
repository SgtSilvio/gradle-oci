package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.ResolvableOciComponent
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:OutputFile
    val digestToMetadataPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val components = mutableMapOf<OciComponent.Capability, ResolvableOciComponent>()
        var rootComponent: ResolvableOciComponent? = null
        for (file in componentFiles) {
            val component = decodeComponent(file.readText())
            val resolvableComponent = ResolvableOciComponent(component)
            if (rootComponent == null) {
                rootComponent = resolvableComponent
            }
            for (capability in component.capabilities) {
                val prevComponent = components.put(capability, resolvableComponent)
                if (prevComponent != null) {
                    throw IllegalStateException("$prevComponent and $component provide the same capability")
                }
            }
        }
        if (rootComponent == null) {
            throw IllegalStateException("componentFiles must contains at least one component json file")
        }
        // TODO
    }
}