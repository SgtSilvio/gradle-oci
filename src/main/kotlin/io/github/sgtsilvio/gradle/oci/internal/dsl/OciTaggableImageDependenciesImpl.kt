package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciTagComponentTask
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.OCI_TAG_CAPABILITY_GROUP
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies.Tag
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciTaggableImageDependenciesImpl @Inject constructor(
    configuration: Configuration,
    dependencyHandler: DependencyHandler,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val configurationContainer: ConfigurationContainer,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
    private val project: Project,
    private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
) : OciImageDependenciesImpl(
    configuration,
    dependencyHandler,
), OciTaggableImageDependencies {

    private var tagConfiguration: Configuration? = null
    private var counter = 0

    class TagImpl(override val imageReference: Provider<String>) : Tag

    // add tagged dependency

//    private fun addTagged(tag: Tag, parentImageConfiguration: Action<in OciImageDependencies>) {
//        val configurationName = configuration.name.removeSuffix("OciImages") // TODO constant
//        val counter = counter++
//        val imageDefinitionName = "${configurationName}Tag$counter"
//        val capability = "$OCI_TAG_CAPABILITY_GROUP:$configurationName-$counter:default"
//        objectFactory.newInstance<OciImageDefinitionImpl>(imageDefinitionName).apply {
//            imageReference.set(tag.imageReference)
//            capabilities.add(capability)
//            allPlatforms {
//                parentImages(parentImageConfiguration)
//            }
//        }
//        add(project) {
//            capabilities {
//                requireCapability(capability)
//            }
//        }
//    }

    private fun getTagConfiguration() = tagConfiguration ?: run {
        val configurationName = configuration.name.removeSuffix("OciImages") // TODO constant
        val capability = "$OCI_TAG_CAPABILITY_GROUP:$configurationName:default"
        val configuration = configurationContainer.create("${configurationName}OciTags") {
            description = "OCI tags for $configurationName"
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            }
            outgoing.capability(capability)
        }
        add(project) {
            capabilities {
                requireCapability(capability) // direct dependency on configuration name is not possible because we can not filter out tags with the capability name
            }
        }
        tagConfiguration = configuration
        configuration
    }

    private fun ModuleDependency.getParentCapabilityForTag(
        projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
    ): Coordinates {
        val capabilities = requestedCapabilities
        return if (capabilities.isEmpty()) {
            getDefaultCapability(projectDependencyPublicationResolver)
        } else {
            val capability = capabilities.first()
            Coordinates(capability.group, capability.name)
        }
    }

    private fun ModuleDependency.getDefaultCapability(
        projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
    ): Coordinates {
        return if (this is ProjectDependency) {
            val id = projectDependencyPublicationResolver.resolve(ModuleVersionIdentifier::class.java, this)
            Coordinates(id.group, id.name)
        } else {
            Coordinates(group ?: "", name)
        }
    }

    private fun addTagComponentTask(
        tagConfiguration: Configuration,
        tag: Tag,
        dependency: Provider<out ModuleDependency>,
    ) {
        val configurationName = configuration.name.removeSuffix("OciImages") // TODO constant
        val counter = counter++
        val task = taskContainer.register<OciTagComponentTask>("$configurationName${counter}OciTagComponent") {
            imageReference.set(tag.imageReference)
            parentCapability.set(dependency.map { it.getParentCapabilityForTag(projectDependencyPublicationResolver) })
            componentFile.set(projectLayout.buildDirectory.file("oci/tags/$configurationName/component-$counter.json"))
        }
        tagConfiguration.outgoing.artifact(task)
    }

    final override fun add(dependency: ModuleDependency, tag: Tag) {
        val tagConfiguration = getTagConfiguration()
        val finalizedDependency = finalizeDependency(dependency)
        tagConfiguration.dependencies.add(finalizedDependency)
        addTagComponentTask(tagConfiguration, tag, providerFactory.provider { finalizedDependency })
    }

//    final override fun add(dependency: ModuleDependency, tag: Tag) = addTagged(tag) { add(dependency) }

    final override fun <D : ModuleDependency> add(dependency: D, tag: Tag, action: Action<in D>) {
        val tagConfiguration = getTagConfiguration()
        val finalizedDependency = finalizeDependency(dependency)
        action.execute(finalizedDependency)
        tagConfiguration.dependencies.add(finalizedDependency)
        addTagComponentTask(tagConfiguration, tag, providerFactory.provider { finalizedDependency })
    }

//    final override fun <D : ModuleDependency> add(dependency: D, tag: Tag, action: Action<in D>) =
//        addTagged(tag) { add(dependency, action) }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>, tag: Tag) {
        val tagConfiguration = getTagConfiguration()
        val finalizedDependencyProvider = dependencyProvider.map { finalizeDependency(it) }
        tagConfiguration.dependencies.addLater(finalizedDependencyProvider)
        addTagComponentTask(tagConfiguration, tag, finalizedDependencyProvider)
    }

//    final override fun add(dependencyProvider: Provider<out ModuleDependency>, tag: Tag) =
//        addTagged(tag) { add(dependencyProvider) }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>) {
        val tagConfiguration = getTagConfiguration()
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizeDependency = finalizeDependency(it)
            action.execute(finalizeDependency)
            finalizeDependency
        }
        tagConfiguration.dependencies.addLater(finalizedDependencyProvider)
        addTagComponentTask(tagConfiguration, tag, finalizedDependencyProvider)
    }

//    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>) =
//        addTagged(tag) { add(dependencyProvider, action) }

    // add tagged dependency converted from a different notation

    final override fun add(dependencyNotation: CharSequence, tag: Tag) = add(createDependency(dependencyNotation), tag)

    final override fun add(dependencyNotation: CharSequence, tag: Tag, action: Action<in ExternalModuleDependency>) =
        add(createDependency(dependencyNotation), tag, action)

    final override fun add(project: Project, tag: Tag) = add(createDependency(project), tag)

    final override fun add(project: Project, tag: Tag, action: Action<in ProjectDependency>) =
        add(createDependency(project), tag, action)

    final override fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, tag: Tag) =
        add(dependencyProvider.asProvider(), tag)

    final override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        tag: Tag,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider.asProvider(), tag, action)
}