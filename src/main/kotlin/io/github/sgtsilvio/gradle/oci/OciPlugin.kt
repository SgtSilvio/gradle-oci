package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.image.LoadOciImageTask
import io.github.sgtsilvio.gradle.oci.image.OciImageLayoutTask
import io.github.sgtsilvio.gradle.oci.image.OciImagesLayoutTask
import io.github.sgtsilvio.gradle.oci.image.PushOciImageTask
import io.github.sgtsilvio.gradle.oci.internal.createOciImageLayoutClassifier
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciExtensionImpl
import io.github.sgtsilvio.gradle.oci.internal.mainToEmpty
import io.github.sgtsilvio.gradle.oci.internal.string.camelCase
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.bundling.Tar
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
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
            registerLayoutTask(this, project, extension)
        }
        registerLayoutTarTaskRule(project)
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
        project.tasks.register<LoadOciImageTask>(loadName.concatCamelCase("ociImage")) {
            group = TASK_GROUP_NAME
            description = "Loads the '${imageDefinition.name}' OCI image to the Docker daemon."
            from(extension.imageDependencies.create(loadName).apply {
                runtime(imageDefinition.dependency).name(imageName).tag(imageTags)
            })
        }
    }

    private fun registerLayoutTask(imageDefinition: OciImageDefinition, project: Project, extension: OciExtension) {
        val imageLayoutClassifier = createOciImageLayoutClassifier(imageDefinition.name)
        project.tasks.register<OciImageLayoutTask>(imageLayoutClassifier.camelCase()) {
            group = TASK_GROUP_NAME
            description = "Creates an OCI image layout directory for the '${imageDefinition.name}' OCI image."
            from(extension.imageDependencies.create(name).apply {
                runtime(imageDefinition.dependency).name(imageName).tag(imageTags)
            })
            destinationDirectory.set(project.layout.buildDirectory.dir("oci/images/${imageDefinition.name}"))
            classifier.set(imageLayoutClassifier)
        }
    }

    private fun registerLayoutTarTaskRule(project: Project) {
        project.tasks.addRule("Pattern: <ociImageLayoutTaskName>Tar") {
            if (endsWith("Tar")) {
                try {
                    val imageLayoutTask = project.tasks.named<OciImagesLayoutTask>(removeSuffix("Tar"))
                    project.tasks.register<Tar>(imageLayoutTask.name.concatCamelCase("tar")) {
                        group = TASK_GROUP_NAME
                        description = "Creates a tar of the OCI image layout created by task '${imageLayoutTask.name}'."
                        from(imageLayoutTask)
                        destinationDirectory.set(imageLayoutTask.flatMap { it.destinationDirectory })
                        archiveClassifier.set(imageLayoutTask.flatMap { it.classifier })
                    }
                } catch (ignored: UnknownTaskException) {
                }
            }
        }
    }
}

const val EXTENSION_NAME = "oci"
const val TASK_GROUP_NAME = "oci"
