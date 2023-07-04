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

    interface Tag {
        val imageReference: Provider<String>
    }

    // add tagged dependency

    fun add(dependency: ModuleDependency, tag: Tag)

    fun <D : ModuleDependency> add(dependency: D, tag: Tag, action: Action<in D>)

    fun add(dependencyProvider: Provider<out ModuleDependency>, tag: Tag)

    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>)

    // add tagged dependency converted from a different notation

    fun add(dependencyNotation: CharSequence, tag: Tag)

    fun add(dependencyNotation: CharSequence, tag: Tag, action: Action<in ExternalModuleDependency>)

    fun add(project: Project, tag: Tag)

    fun add(project: Project, tag: Tag, action: Action<in ProjectDependency>)

    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, tag: Tag)

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        tag: Tag,
        action: Action<in ExternalModuleDependency>,
    )

    // dsl syntactic sugar for adding tagged dependency

    operator fun invoke(dependency: ModuleDependency, tag: Tag) = add(dependency, tag)

    operator fun <D : ModuleDependency> invoke(dependency: D, tag: Tag, action: Action<in D>) =
        add(dependency, tag, action)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>, tag: Tag) = add(dependencyProvider, tag)

    operator fun <D : ModuleDependency> invoke(dependencyProvider: Provider<out D>, tag: Tag, action: Action<in D>) =
        add(dependencyProvider, tag, action)

    // dsl syntactic sugar for adding tagged dependency converted from a different notation

    operator fun invoke(dependencyNotation: CharSequence, tag: Tag) = add(dependencyNotation, tag)

    operator fun invoke(dependencyNotation: CharSequence, tag: Tag, action: Action<in ExternalModuleDependency>) =
        add(dependencyNotation, tag, action)

    operator fun invoke(project: Project, tag: Tag) = add(project, tag)

    operator fun invoke(project: Project, tag: Tag, action: Action<in ProjectDependency>) = add(project, tag, action)

    operator fun invoke(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, tag: Tag) =
        add(dependencyProvider, tag)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        tag: Tag,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider, tag, action)
}