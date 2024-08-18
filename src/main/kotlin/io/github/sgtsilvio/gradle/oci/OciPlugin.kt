package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PlatformAttributeCompatibilityRule
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesExtension
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciExtensionImpl
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciImageDependenciesExtensionImpl
import io.github.sgtsilvio.gradle.oci.internal.mainToEmpty
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register

/**
 * @author Silvio Giebl
 */
class OciPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.dependencies.attributesSchema.apply {
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE)
            attribute(PLATFORM_ATTRIBUTE)
            getMatchingStrategy(PLATFORM_ATTRIBUTE).compatibilityRules.add(PlatformAttributeCompatibilityRule::class)
            attribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE)
        }
        val extension = project.extensions.create(OciExtension::class, EXTENSION_NAME, OciExtensionImpl::class)
        registerPushTasks(project, extension)
        project.extensions.create(
            OciImageDependenciesExtension::class,
            IMAGE_DEPENDENCIES_EXTENSION_NAME,
            OciImageDependenciesExtensionImpl::class,
            extension,
        )
    }

    private fun registerPushTasks(project: Project, extension: OciExtension) {
        extension.imageDefinitions.all {
            val imageDefName = name
            val pushName = "push".concatCamelCase(imageDefName.mainToEmpty())
            val pushTask = project.tasks.register<OciPushSingleTask>(pushName.concatCamelCase("ociImage"))
            val pushImageDependencies = extension.imageDependencies.create(pushName).apply {
                runtime(dependency).name(pushTask.flatMap { it.imageName }).tag(pushTask.flatMap { it.imageTags })
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
const val IMAGE_DEPENDENCIES_EXTENSION_NAME = "ociImageDependencies"
const val TASK_GROUP_NAME = "oci"
