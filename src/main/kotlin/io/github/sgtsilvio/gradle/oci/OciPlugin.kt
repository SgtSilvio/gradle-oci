package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.internal.concatCamelCase
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciExtensionImpl
import io.github.sgtsilvio.gradle.oci.internal.mainToEmpty
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register

/**
 * @author Silvio Giebl
 */
class OciPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.dependencies.attributesSchema.attribute(DISTRIBUTION_TYPE_ATTRIBUTE)
        val extension = project.extensions.create(OciExtension::class, EXTENSION_NAME, OciExtensionImpl::class)
        registerPushTasks(project, extension)
    }

    private fun registerPushTasks(project: Project, extension: OciExtension) {
        extension.imageDefinitions.all {
            val imageDefName = name
            val pushName = "push".concatCamelCase(name.mainToEmpty())
            val pushTask = project.tasks.register<OciPushSingleTask>(pushName.concatCamelCase("ociImage"))
            val pushImageDependencies = extension.imageDependencies.create(pushName) {
                add(project) {
                    capabilities {
                        for (capability in capabilities.set.get()) {
                            requireCapability("${capability.group}:${capability.name}")
                        }
                    }
                }.name(pushTask.flatMap { it.imageName }).tag(pushTask.flatMap { it.imageTags })
            }
            pushTask {
                group = TASK_GROUP_NAME
                description = "Pushes the $imageDefName OCI image to a registry."
                from(pushImageDependencies)
            }
        }
    }
}

const val EXTENSION_NAME = "oci"
const val TASK_GROUP_NAME = "oci"
