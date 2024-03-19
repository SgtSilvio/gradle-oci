package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.withType

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependenciesImpl<T>(
    final override val configuration: Configuration,
    private val dependencyHandler: DependencyHandler,
) : OciImageDependencies<T> {

    final override val set get() = configuration.allDependencies.withType(ModuleDependency::class)

    // add dependency

    final override fun add(dependency: ModuleDependency): T {
        val finalizedDependency = finalizeDependency(dependency)
        configuration.dependencies.add(finalizedDependency)
        return returnType(finalizedDependency)
    }

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>): T {
        val finalizedDependency = finalizeDependency(dependency)
        action.execute(finalizedDependency)
        configuration.dependencies.add(finalizedDependency)
        return returnType(finalizedDependency)
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>): T {
        val finalizedDependencyProvider = dependencyProvider.map { finalizeDependency(it) }
        configuration.dependencies.addLater(finalizedDependencyProvider)
        return returnType(finalizedDependencyProvider)
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>): T {
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizedDependency = finalizeDependency(it)
            action.execute(finalizedDependency)
            finalizedDependency
        }
        configuration.dependencies.addLater(finalizedDependencyProvider)
        return returnType(finalizedDependencyProvider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <D : ModuleDependency> finalizeDependency(dependency: D) = dependencyHandler.create(dependency) as D

    abstract fun returnType(dependency: ModuleDependency): T

    abstract fun returnType(dependencyProvider: Provider<out ModuleDependency>): T

    // add dependency converted from a different notation

    private fun module(dependencyNotation: CharSequence) =
        dependencyHandler.create(dependencyNotation) as ExternalModuleDependency

    private fun project(project: Project) = dependencyHandler.create(project) as ProjectDependency

    final override fun add(dependencyNotation: CharSequence) = add(module(dependencyNotation))

    final override fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>) =
        add(module(dependencyNotation), action)

    final override fun add(project: Project) = add(project(project))

    final override fun add(project: Project, action: Action<in ProjectDependency>) = add(project(project), action)

    // add constraint

    final override fun add(dependencyConstraint: DependencyConstraint) {
        configuration.dependencyConstraints.add(dependencyConstraint)
    }

    final override fun add(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>) {
        action.execute(dependencyConstraint)
        configuration.dependencyConstraints.add(dependencyConstraint)
    }

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    final override fun add(dependencyConstraintProvider: Provider<out DependencyConstraint>) =
        configuration.dependencyConstraints.addLater(dependencyConstraintProvider)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    final override fun add(
        dependencyConstraintProvider: Provider<out DependencyConstraint>,
        action: Action<in DependencyConstraint>,
    ) = configuration.dependencyConstraints.addLater(dependencyConstraintProvider.map { action.execute(it); it })

    // create constraint from a different notation

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        dependencyHandler.constraints.create(dependencyConstraintNotation)

    final override fun constraint(project: Project): DependencyConstraint =
        dependencyHandler.constraints.create(project)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyHandler.constraints.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}
