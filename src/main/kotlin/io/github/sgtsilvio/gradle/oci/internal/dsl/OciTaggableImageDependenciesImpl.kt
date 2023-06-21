package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
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

    private fun addTagged(parentImageConfiguration: Action<in OciImageDependencies>): OciImageDefinitionImpl {
        val configurationName = configuration.name.removeSuffix("OciImages") // TODO constant
        val counter = counter++
        val imageDefinitionName = "${configurationName}Tag$counter"
        val capability = "io.github.sgtsilvio.gradle.oci.tag:$configurationName-$counter:default"
        val imageDefinition = objectFactory.newInstance<OciImageDefinitionImpl>(imageDefinitionName).apply {
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
        return imageDefinition
    }

    private fun addTagged(imageReference: String, parentImageConfiguration: Action<in OciImageDependencies>) =
        addTagged(parentImageConfiguration).imageReference.set(imageReference)

    private fun addTagged(
        imageReferenceProvider: Provider<String>,
        parentImageConfiguration: Action<in OciImageDependencies>,
    ) = addTagged(parentImageConfiguration).imageReference.set(imageReferenceProvider)

    override fun add(dependency: ModuleDependency, imageReference: String) =
        addTagged(imageReference) { add(dependency) }

    override fun add(dependency: ModuleDependency, imageReferenceProvider: Provider<String>) =
        addTagged(imageReferenceProvider) { add(dependency) }

    override fun <D : ModuleDependency> add(dependency: D, imageReference: String, action: Action<in D>) =
        addTagged(imageReference) { add(dependency, action) }

    override fun <D : ModuleDependency> add(
        dependency: D,
        imageReferenceProvider: Provider<String>,
        action: Action<in D>,
    ) = addTagged(imageReferenceProvider) { add(dependency, action) }

    override fun add(dependencyProvider: Provider<out ModuleDependency>, imageReference: String) =
        addTagged(imageReference) { add(dependencyProvider) }

    override fun add(dependencyProvider: Provider<out ModuleDependency>, imageReferenceProvider: Provider<String>) =
        addTagged(imageReferenceProvider) { add(dependencyProvider) }

    override fun <D : ModuleDependency> add(
        dependencyProvider: Provider<out D>,
        imageReference: String,
        action: Action<in D>,
    ) = addTagged(imageReference) { add(dependencyProvider, action) }

    override fun <D : ModuleDependency> add(
        dependencyProvider: Provider<out D>,
        imageReferenceProvider: Provider<String>,
        action: Action<in D>,
    ) = addTagged(imageReferenceProvider) { add(dependencyProvider, action) }

    override fun add(dependencyNotation: CharSequence, imageReference: String) =
        add(module(dependencyNotation), imageReference)

    override fun add(dependencyNotation: CharSequence, imageReferenceProvider: Provider<String>) =
        add(module(dependencyNotation), imageReferenceProvider)

    override fun add(
        dependencyNotation: CharSequence,
        imageReference: String,
        action: Action<in ExternalModuleDependency>,
    ) = add(module(dependencyNotation), imageReference, action)

    override fun add(
        dependencyNotation: CharSequence,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    ) = add(module(dependencyNotation), imageReferenceProvider, action)

    override fun add(project: Project, imageReference: String) = add(project(project), imageReference)

    override fun add(project: Project, imageReferenceProvider: Provider<String>) =
        add(project(project), imageReferenceProvider)

    override fun add(project: Project, imageReference: String, action: Action<in ProjectDependency>) =
        add(project(project), imageReference, action)

    override fun add(project: Project, imageReferenceProvider: Provider<String>, action: Action<in ProjectDependency>) =
        add(project(project), imageReferenceProvider, action)

    override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReference: String,
    ) = add(dependencyProvider.asProvider(), imageReference)

    override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
    ) = add(dependencyProvider.asProvider(), imageReferenceProvider)

    override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReference: String,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider.asProvider(), imageReference, action)

    override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider.asProvider(), imageReferenceProvider, action)
}