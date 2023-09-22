package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.OciComponentBuilder
import io.github.sgtsilvio.gradle.oci.component.OciComponentBundleBuilder
import io.github.sgtsilvio.gradle.oci.component.encodeToJsonString
import io.github.sgtsilvio.gradle.oci.mapping.toOciImageReference
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

/**
 * @author Silvio Giebl
 */
abstract class OciTagComponentTask : DefaultTask() {

    @Input
    val imageReference = project.objects.property<String>()

    @Input
    val parentCapability = project.objects.property<Coordinates>()

    @OutputFile
    val componentFile: RegularFileProperty = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val component =
            OciComponentBuilder().imageReference(imageReference.get().toOciImageReference()).bundleOrPlatformBundles(
                OciComponentBundleBuilder().parentCapabilities(listOf(parentCapability.get())).build()
            ).build()
        componentFile.get().asFile.writeText(component.encodeToJsonString())
    }
}