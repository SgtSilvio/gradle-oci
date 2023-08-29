package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import org.gradle.kotlin.dsl.withType
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciImageDependenciesImpl @Inject constructor(
    private val _configuration: Configuration,
    protected val dependencyHandler: DependencyHandler,
) : OciImageDependencies {

    override val configuration = _configuration
    final override val set get() = configuration.allDependencies.withType(ModuleDependency::class)

    // add dependency

    final override fun add(dependency: ModuleDependency) {
        _configuration.addDependency(dependency)
    }

    protected fun Configuration.addDependency(dependency: ModuleDependency): ModuleDependency {
        val finalizedDependency = finalizeDependency(dependency)
        dependencies.add(finalizedDependency)
        return finalizedDependency
    }

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>) {
        _configuration.addDependency(dependency, action)
    }

    protected fun <D : ModuleDependency> Configuration.addDependency(dependency: D, action: Action<in D>): D {
        val finalizedDependency = finalizeDependency(dependency)
        action.execute(finalizedDependency)
        dependencies.add(finalizedDependency)
        return finalizedDependency
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>) {
        _configuration.addDependency(dependencyProvider)
    }

    protected fun Configuration.addDependency(
        dependencyProvider: Provider<out ModuleDependency>,
    ): Provider<ModuleDependency> {
        val finalizedDependencyProvider = dependencyProvider.map { finalizeDependency(it) }
        dependencies.addLater(finalizedDependencyProvider)
        return finalizedDependencyProvider
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>) {
        _configuration.addDependency(dependencyProvider, action)
    }

    protected fun <D : ModuleDependency> Configuration.addDependency(
        dependencyProvider: Provider<out D>,
        action: Action<in D>,
    ): Provider<D> {
        val finalizedDependencyProvider = dependencyProvider.map {
            val finalizedDependency = finalizeDependency(it)
            action.execute(finalizedDependency)
            finalizedDependency
        }
        dependencies.addLater(finalizedDependencyProvider)
        return finalizedDependencyProvider
    }

    @Suppress("UNCHECKED_CAST")
    private fun <D : ModuleDependency> finalizeDependency(dependency: D) = dependencyHandler.create(dependency) as D

    // add dependency converted from a different notation

    protected fun createDependency(dependencyNotation: CharSequence) =
        dependencyHandler.create(dependencyNotation) as ExternalModuleDependency

    protected fun createDependency(project: Project) = dependencyHandler.create(project) as ProjectDependency

    final override fun add(dependencyNotation: CharSequence) = add(createDependency(dependencyNotation))

    final override fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>) =
        add(createDependency(dependencyNotation), action)

    final override fun add(project: Project) = add(createDependency(project))

    final override fun add(project: Project, action: Action<in ProjectDependency>) =
        add(createDependency(project), action)

    final override fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        add(dependencyProvider.asProvider())

    final override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in ExternalModuleDependency>
    ) = add(dependencyProvider.asProvider(), action)

    // add constraint

    final override fun constraint(dependencyConstraint: DependencyConstraint) {
        _configuration.dependencyConstraints.add(dependencyConstraint)
    }

    final override fun constraint(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>) {
        action.execute(dependencyConstraint)
        _configuration.dependencyConstraints.add(dependencyConstraint)
    }

    // add constraint converted from a different notation

    private fun createDependencyConstraint(dependencyNotation: CharSequence): DependencyConstraint =
        dependencyHandler.constraints.create(dependencyNotation)

    private fun createDependencyConstraint(project: Project): DependencyConstraint =
        dependencyHandler.constraints.create(project)

    private fun createDependencyConstraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyHandler.constraints.create(dependency)

    final override fun constraint(dependencyConstraintNotation: CharSequence) =
        constraint(createDependencyConstraint(dependencyConstraintNotation))

    final override fun constraint(dependencyConstraintNotation: CharSequence, action: Action<in DependencyConstraint>) =
        constraint(createDependencyConstraint(dependencyConstraintNotation), action)

    final override fun constraint(project: Project) = constraint(createDependencyConstraint(project))

    final override fun constraint(project: Project, action: Action<in DependencyConstraint>) =
        constraint(createDependencyConstraint(project), action)

    final override fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>) =
        _configuration.dependencyConstraints.addLater(dependencyProvider.map { createDependencyConstraint(it) })

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
        action: Action<in DependencyConstraint>,
    ) = _configuration.dependencyConstraints.addLater(dependencyProvider.map {
        val dependencyConstraint = createDependencyConstraint(it)
        action.execute(dependencyConstraint)
        dependencyConstraint
    })

    final override fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        constraint(dependencyProvider.asProvider())

    final override fun constraint(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in DependencyConstraint>,
    ) = constraint(dependencyProvider.asProvider(), action)
}
