package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciImageDependenciesImpl @Inject constructor(
    private val configuration: Configuration,
    private val dependencyHandler: DependencyHandler,
) : OciImageDependencies {

    final override fun add(dependency: ModuleDependency) {
        val finalizedDependency = finalizeDependency(dependency)
        dependencies.add(finalizedDependency)
        configuration.dependencies.add(finalizedDependency)
    }

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>) {
        val finalizedDependency = finalizeDependency(dependency)
        action.execute(finalizedDependency)
        dependencies.add(finalizedDependency)
        configuration.dependencies.add(finalizedDependency)
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>) {
        val finalizedDependencyProvider = dependencyProvider.map { finalizeDependency(it) }
        dependencies.addLater(finalizedDependencyProvider)
        configuration.dependencies.addLater(finalizedDependencyProvider)
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>) {
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizedDependency = finalizeDependency(it)
            action.execute(finalizedDependency)
            finalizedDependency
        }
        dependencies.addLater(finalizedDependencyProvider)
        configuration.dependencies.addLater(finalizedDependencyProvider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <D : ModuleDependency> finalizeDependency(dependency: D) = dependencyHandler.create(dependency) as D

    final override fun module(dependencyNotation: CharSequence) =
        dependencyHandler.create(dependencyNotation) as ExternalModuleDependency

    final override fun module(dependencyProvider: Provider<out MinimalExternalModuleDependency>) =
        dependencyProvider.map { dependencyHandler.create(it) as ExternalModuleDependency }

    private fun project(project: Project) = dependencyHandler.create(project) as ProjectDependency

    final override fun add(dependencyNotation: CharSequence) = add(module(dependencyNotation))

    final override fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>) =
        add(module(dependencyNotation), action)

    final override fun add(project: Project) = add(project(project))

    final override fun add(project: Project, action: Action<in ProjectDependency>) = add(project(project), action)
}