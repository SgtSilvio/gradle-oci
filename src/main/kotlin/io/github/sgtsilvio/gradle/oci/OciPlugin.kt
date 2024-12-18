package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.image.OciPushSingleTask
import io.github.sgtsilvio.gradle.oci.image.PullToDockerTask
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciExtensionImpl
import io.github.sgtsilvio.gradle.oci.internal.mainToEmpty
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

/**
 * @author Silvio Giebl
 */
class OciPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.dependencies.attributesSchema.apply {
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE)
            getMatchingStrategy(DISTRIBUTION_TYPE_ATTRIBUTE).apply {
                compatibilityRules.add(OciDistributionTypeCompatibilityRule::class)
                disambiguationRules.add(OciDistributionTypeDisambiguationRule::class)
            }
            attribute(PLATFORM_ATTRIBUTE)
            attribute(OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE)
            attribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE)
        }
        val extension = project.extensions.create(OciExtension::class, EXTENSION_NAME, OciExtensionImpl::class)
        registerPushTasks(project, extension)
    }

    private fun registerPushTasks(project: Project, extension: OciExtension) {
        extension.imageDefinitions.all {
            val imageDefName = name
            val pushName = "push".concatCamelCase(imageDefName.mainToEmpty())
            project.tasks.register<OciPushSingleTask>(pushName.concatCamelCase("ociImage")) {
                group = TASK_GROUP_NAME
                description = "Pushes the '$imageDefName' OCI image to a registry."
                from(extension.imageDependencies.create(pushName).apply {
                    runtime(dependency).name(imageName).tag(imageTags)
                })
            }

            val pullName = "pull".concatCamelCase(imageDefName.mainToEmpty())
            project.tasks.register<PullToDockerTask>(pullName.concatCamelCase("ociImage")) { // TODO toDocker
                group = TASK_GROUP_NAME
                description = "Pulls the '$imageDefName' OCI image to the Docker daemon."
                from(extension.imageDependencies.create(pullName).apply {
                    runtime(dependency).name(imageName).tag(imageTags)
                })
            }
        }
    }
}

const val EXTENSION_NAME = "oci"
const val TASK_GROUP_NAME = "oci"
