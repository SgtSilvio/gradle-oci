package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciTagComponentTask
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies.Tag
import io.github.sgtsilvio.gradle.oci.internal.gradle.getAnyCapability
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
    private val prefix: String,
    description: String,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    configurationContainer: ConfigurationContainer,
    dependencyHandler: DependencyHandler,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
    private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
) : OciImageDependenciesImpl(
    configurationContainer.create(prefix + "OciImages") {
        this.description = description
        isCanBeConsumed = false
        isCanBeResolved = false
    },
    dependencyHandler,
), OciTaggableImageDependencies {

    final override val configuration = configurationContainer.create(prefix + "OciTaggableImages") {
        this.description = "$description (taggable)"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
        }
        extendsFrom(super.configuration)
    }
    private var counter = 0

    class TagImpl(override val imageReference: Provider<String>) : Tag

    // add tagged dependency

    private fun addTagComponentTask(tag: Tag, dependency: Provider<out ModuleDependency>) {
        val counter = counter++
        val task = taskContainer.register<OciTagComponentTask>("$prefix${counter}OciTagComponent") {
            imageReference.set(tag.imageReference)
            parentCapability.set(dependency.map { it.getAnyCapability(projectDependencyPublicationResolver) })
            componentFile.set(projectLayout.buildDirectory.file("oci/tags/$prefix/component-$counter.json"))
        }
        configuration.dependencies.add(dependencyHandler.create(objectFactory.fileCollection().from(task)))
    }

    final override fun add(dependency: ModuleDependency, tag: Tag) {
        val finalizedDependency = configuration.addDependency(dependency)
        addTagComponentTask(tag, providerFactory.provider { finalizedDependency })
    }

    final override fun <D : ModuleDependency> add(dependency: D, tag: Tag, action: Action<in D>) {
        val finalizedDependency = configuration.addDependency(dependency, action)
        addTagComponentTask(tag, providerFactory.provider { finalizedDependency })
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>, tag: Tag) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider)
        addTagComponentTask(tag, finalizedDependencyProvider)
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider, action)
        addTagComponentTask(tag, finalizedDependencyProvider)
    }

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
