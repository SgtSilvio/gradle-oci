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
interface OciImageDependencies<T> : DependencyConstraintFactories {

    val configuration: Configuration
    val set: DomainObjectSet<ModuleDependency>

    // add dependency

    fun add(dependency: ModuleDependency): T

    fun <D : ModuleDependency> add(dependency: D, action: Action<in D>): T

    fun add(dependencyProvider: Provider<out ModuleDependency>): T

    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>): T

    // add dependency converted from a different notation

    fun add(dependencyNotation: CharSequence): T

    fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>): T

    fun add(project: Project): T

    fun add(project: Project, action: Action<in ProjectDependency>): T

    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        add(dependencyProvider.asProvider())

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider.asProvider(), action)

    // add constraint

    fun add(dependencyConstraint: DependencyConstraint)

    fun add(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    fun add(dependencyConstraintProvider: Provider<out DependencyConstraint>)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    fun add(
        dependencyConstraintProvider: Provider<out DependencyConstraint>,
        action: Action<in DependencyConstraint>,
    )

    // dsl syntactic sugar for adding dependency

    operator fun invoke(dependency: ModuleDependency) = add(dependency)

    operator fun <D : ModuleDependency> invoke(dependency: D, action: Action<in D>) = add(dependency, action)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>) = add(dependencyProvider)

    operator fun <D : ModuleDependency> invoke(dependencyProvider: Provider<out D>, action: Action<in D>) =
        add(dependencyProvider, action)

    // dsl syntactic sugar for adding dependency converted from a different notation

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

    // dsl syntactic sugar for adding dependency constraint

    operator fun invoke(dependencyConstraint: DependencyConstraint) = add(dependencyConstraint)

    operator fun invoke(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>) =
        add(dependencyConstraint, action)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("invokeConstraint")
    operator fun invoke(dependencyConstraintProvider: Provider<out DependencyConstraint>) =
        add(dependencyConstraintProvider)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("invokeConstraint")
    operator fun invoke(
        dependencyConstraintProvider: Provider<out DependencyConstraint>,
        action: Action<in DependencyConstraint>,
    ) = add(dependencyConstraintProvider, action)
}
