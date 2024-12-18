package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.image.LoadOciImagesTask
import io.github.sgtsilvio.gradle.oci.image.PushOciImageTask
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
        registerImageTasks(project, extension)
    }

    private fun registerImageTasks(project: Project, extension: OciExtension) {
        extension.imageDefinitions.all {
            registerPushTask(this, project, extension)
            registerLoadTask(this, project, extension)
        }
    }

    private fun registerPushTask(imageDefinition: OciImageDefinition, project: Project, extension: OciExtension) {
        val pushName = "push".concatCamelCase(imageDefinition.name.mainToEmpty())
        project.tasks.register<PushOciImageTask>(pushName.concatCamelCase("ociImage")) {
            group = TASK_GROUP_NAME
            description = "Pushes the '${imageDefinition.name}' OCI image to a registry."
            from(extension.imageDependencies.create(pushName).apply {
                runtime(imageDefinition.dependency).name(imageName).tag(imageTags)
            })
        }
    }

    private fun registerLoadTask(imageDefinition: OciImageDefinition, project: Project, extension: OciExtension) {
        val loadName = "load".concatCamelCase(imageDefinition.name.mainToEmpty())
        project.tasks.register<LoadOciImagesTask>(loadName.concatCamelCase("ociImage")) {
            group = TASK_GROUP_NAME
            description = "Loads the '${imageDefinition.name}' OCI image to the Docker daemon."
            from(extension.imageDependencies.create(loadName).apply {
                runtime(imageDefinition.dependency).name(imageName).tag(imageTags)
            })
        }
    }
}

const val EXTENSION_NAME = "oci"
const val TASK_GROUP_NAME = "oci"
