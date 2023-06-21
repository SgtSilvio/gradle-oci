package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface OciTaggableImageDependencies : OciImageDependencies {

    fun add(dependency: ModuleDependency, imageReference: String)

    fun add(dependency: ModuleDependency, imageReferenceProvider: Provider<String>)

    fun <D : ModuleDependency> add(dependency: D, imageReference: String, action: Action<in D>)

    fun <D : ModuleDependency> add(dependency: D, imageReferenceProvider: Provider<String>, action: Action<in D>)

    fun add(dependencyProvider: Provider<out ModuleDependency>, imageReference: String)

    fun add(dependencyProvider: Provider<out ModuleDependency>, imageReferenceProvider: Provider<String>)

    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, imageReference: String, action: Action<in D>)

    fun <D : ModuleDependency> add(
        dependencyProvider: Provider<out D>,
        imageReferenceProvider: Provider<String>, action: Action<in D>,
    )


    fun add(dependencyNotation: CharSequence, imageReference: String)

    fun add(dependencyNotation: CharSequence, imageReferenceProvider: Provider<String>)

    fun add(dependencyNotation: CharSequence, imageReference: String, action: Action<in ExternalModuleDependency>)

    fun add(
        dependencyNotation: CharSequence,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    )

    fun add(project: Project, imageReference: String)

    fun add(project: Project, imageReferenceProvider: Provider<String>)

    fun add(project: Project, imageReference: String, action: Action<in ProjectDependency>)

    fun add(project: Project, imageReferenceProvider: Provider<String>, action: Action<in ProjectDependency>)

    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, imageReference: String)

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
    )

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReference: String,
        action: Action<in ExternalModuleDependency>,
    )

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    )


    // from here: dsl syntactic sugar

    operator fun invoke(dependency: ModuleDependency, imageReference: String) = add(dependency, imageReference)

    operator fun invoke(dependency: ModuleDependency, imageReferenceProvider: Provider<String>) =
        add(dependency, imageReferenceProvider)

    operator fun <D : ModuleDependency> invoke(dependency: D, imageReference: String, action: Action<in D>) =
        add(dependency, imageReference, action)

    operator fun <D : ModuleDependency> invoke(
        dependency: D,
        imageReferenceProvider: Provider<String>,
        action: Action<in D>,
    ) = add(dependency, imageReferenceProvider, action)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>, imageReference: String) =
        add(dependencyProvider, imageReference)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>, imageReferenceProvider: Provider<String>) =
        add(dependencyProvider, imageReferenceProvider)

    operator fun <D : ModuleDependency> invoke(
        dependencyProvider: Provider<out D>,
        imageReference: String,
        action: Action<in D>,
    ) = add(dependencyProvider, imageReference, action)

    operator fun <D : ModuleDependency> invoke(
        dependencyProvider: Provider<out D>,
        imageReferenceProvider: Provider<String>,
        action: Action<in D>,
    ) = add(dependencyProvider, imageReferenceProvider, action)


    operator fun invoke(dependencyNotation: CharSequence, imageReference: String) =
        add(dependencyNotation, imageReference)

    operator fun invoke(dependencyNotation: CharSequence, imageReferenceProvider: Provider<String>) =
        add(dependencyNotation, imageReferenceProvider)

    operator fun invoke(
        dependencyNotation: CharSequence,
        imageReference: String,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyNotation, imageReference, action)

    operator fun invoke(
        dependencyNotation: CharSequence,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyNotation, imageReferenceProvider, action)

    operator fun invoke(project: Project, imageReference: String) = add(project, imageReference)

    operator fun invoke(project: Project, imageReferenceProvider: Provider<String>) =
        add(project, imageReferenceProvider)

    operator fun invoke(project: Project, imageReference: String, action: Action<in ProjectDependency>) =
        add(project, imageReference, action)

    operator fun invoke(
        project: Project,
        imageReferenceProvider: Provider<String>,
        action: Action<in ProjectDependency>,
    ) = add(project, imageReferenceProvider, action)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReference: String,
    ) = add(dependencyProvider, imageReference)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
    ) = add(dependencyProvider, imageReferenceProvider)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReference: String,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider, imageReference, action)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        imageReferenceProvider: Provider<String>,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider, imageReferenceProvider, action)
}