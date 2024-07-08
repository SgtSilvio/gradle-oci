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
) : DependencyConstraintFactoriesImpl(dependencyHandler.constraints), OciImageDependencies<T> {

    final override val set get() = configuration.allDependencies.withType(ModuleDependency::class)

    // add dependency

    final override fun add(dependency: ModuleDependency): T {
        val finalizedDependency = finalizeDependency(dependency)
        val returnValue = newReturnValue()
        associateDependencyAndReturnValue(finalizedDependency, returnValue)
        configuration.dependencies.add(finalizedDependency)
        return returnValue
    }

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>): T {
        val finalizedDependency = finalizeDependency(dependency)
        action.execute(finalizedDependency)
        val returnValue = newReturnValue()
        associateDependencyAndReturnValue(finalizedDependency, returnValue)
        configuration.dependencies.add(finalizedDependency)
        return returnValue
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>): T {
        val returnValue = newReturnValue()
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizedDependency = finalizeDependency(it)
            associateDependencyAndReturnValue(finalizedDependency, returnValue)
            finalizedDependency
        }
        configuration.dependencies.addLater(finalizedDependencyProvider)
        return returnValue
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>): T {
        val returnValue = newReturnValue()
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizedDependency = finalizeDependency(it)
            action.execute(finalizedDependency)
            associateDependencyAndReturnValue(finalizedDependency, returnValue)
            finalizedDependency
        }
        configuration.dependencies.addLater(finalizedDependencyProvider)
        return returnValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun <D : ModuleDependency> finalizeDependency(dependency: D) = dependencyHandler.create(dependency) as D

    protected abstract fun newReturnValue(): T

    protected open fun associateDependencyAndReturnValue(dependency: ModuleDependency, returnValue: T) {}

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
}
