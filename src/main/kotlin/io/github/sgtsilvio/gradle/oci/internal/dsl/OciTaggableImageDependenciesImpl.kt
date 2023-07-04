package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies.Tag
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciTaggableImageDependenciesImpl @Inject constructor(
    configuration: Configuration,
    dependencyHandler: DependencyHandler,
    private val objectFactory: ObjectFactory,
    private val project: Project,
) : OciImageDependenciesImpl(
    configuration,
    dependencyHandler,
), OciTaggableImageDependencies {

    private var counter = 0

    class TagImpl(override val imageReference: Provider<String>) : Tag

    // add tagged dependency

    private fun addTagged(tag: Tag, parentImageConfiguration: Action<in OciImageDependencies>) {
        val configurationName = configuration.name.removeSuffix("OciImages") // TODO constant
        val counter = counter++
        val imageDefinitionName = "${configurationName}Tag$counter"
        val capability = "io.github.sgtsilvio.gradle.oci.tag:$configurationName-$counter:default" // TODO group constant
        objectFactory.newInstance<OciImageDefinitionImpl>(imageDefinitionName).apply {
            imageReference.set(tag.imageReference)
            capabilities.add(capability)
            allPlatforms {
                parentImages(parentImageConfiguration)
            }
        }
        add(project) {
            capabilities {
                requireCapability(capability)
            }
        }
        // TODO indexAnnotations, author, creationTime of parent image not used
        // TODO handle tags differently: no capability in component.json, only bundle with exactly one parent allowed
        // TODO resolve only parent, don't add tag component to resolver, use resolved parent for metadata generation, only use imageReference from tag component
    }

    final override fun add(dependency: ModuleDependency, tag: Tag) = addTagged(tag) { add(dependency) }

    final override fun <D : ModuleDependency> add(dependency: D, tag: Tag, action: Action<in D>) =
        addTagged(tag) { add(dependency, action) }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>, tag: Tag) =
        addTagged(tag) { add(dependencyProvider) }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>) =
        addTagged(tag) { add(dependencyProvider, action) }

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