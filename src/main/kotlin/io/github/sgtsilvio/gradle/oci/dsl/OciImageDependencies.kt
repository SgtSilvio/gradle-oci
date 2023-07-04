package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies {

    val configuration: Configuration
    val set: DomainObjectSet<ModuleDependency>

    fun add(dependency: ModuleDependency)

    fun <D : ModuleDependency> add(dependency: D, action: Action<in D>)

    fun add(dependencyProvider: Provider<out ModuleDependency>)

    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>)


    fun add(dependencyNotation: CharSequence)

    fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>)

    fun add(project: Project)

    fun add(project: Project, action: Action<in ProjectDependency>)

    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>)

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in ExternalModuleDependency>,
    )


    fun constraint(dependencyConstraint: DependencyConstraint)

    fun constraint(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>)


    fun constraint(dependencyConstraintNotation: CharSequence)

    fun constraint(dependencyConstraintNotation: CharSequence, action: Action<in DependencyConstraint>)

    fun constraint(project: Project)

    fun constraint(project: Project, action: Action<in DependencyConstraint>)

    fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>)

    fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
        action: Action<in DependencyConstraint>,
    )

    fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>)

    fun constraint(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in DependencyConstraint>,
    )


    // from here: dsl syntactic sugar

    operator fun invoke(dependency: ModuleDependency) = add(dependency)

    operator fun <D : ModuleDependency> invoke(dependency: D, action: Action<in D>) = add(dependency, action)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>) = add(dependencyProvider)

    operator fun <D : ModuleDependency> invoke(dependencyProvider: Provider<out D>, action: Action<in D>) =
        add(dependencyProvider, action)


    operator fun invoke(dependencyNotation: CharSequence) = add(dependencyNotation)

    operator fun invoke(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>) =
        add(dependencyNotation, action)

    operator fun invoke(project: Project) = add(project)

    operator fun invoke(project: Project, action: Action<in ProjectDependency>) = add(project, action)

    operator fun invoke(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        add(dependencyProvider)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider, action)
}