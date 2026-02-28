package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencyCollectorBase
import io.github.sgtsilvio.gradle.oci.internal.gradle.createDependency
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.setProperty

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependencyCollectorBaseImpl<T>(
    private val dependencyHandler: DependencyHandler,
    objectFactory: ObjectFactory,
) : OciImageDependencyCollectorBase<T> {

    final override val dependencies = objectFactory.setProperty<ModuleDependency>()
    final override val dependencyConstraints = objectFactory.setProperty<DependencyConstraint>()

    // add dependency

    final override fun add(dependency: ModuleDependency) = addInternal(dependency, null)

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>) = addInternal(dependency, action)

    final override fun add(dependencyProvider: Provider<out ModuleDependency>) = addInternal(dependencyProvider, null)

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>) =
        addInternal(dependencyProvider, action)

    private fun <D : ModuleDependency> addInternal(dependency: D, action: Action<in D>?) =
        addInternal(finalizeDependency(dependency, action))

    private fun <D : ModuleDependency> addInternal(dependencyProvider: Provider<out D>, action: Action<in D>?) =
        addInternal(dependencyProvider.map { finalizeDependency(it, action) })

    protected abstract fun addInternal(dependency: ModuleDependency): T

    protected abstract fun addInternal(dependencyProvider: Provider<out ModuleDependency>): T

    private fun <D : ModuleDependency> finalizeDependency(dependency: D, action: Action<in D>?): D {
        @Suppress("UNCHECKED_CAST") val finalizedDependency = dependencyHandler.create(dependency) as D
        action?.execute(finalizedDependency)
        return finalizedDependency
    }

    // add dependency converted from a different notation

    private fun module(dependencyNotation: CharSequence) =
        dependencyHandler.create(dependencyNotation) as ExternalModuleDependency

    final override fun add(dependencyNotation: CharSequence) = add(module(dependencyNotation))

    final override fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>) =
        add(module(dependencyNotation), action)

    final override fun add(project: Project) = add(project.createDependency())

    final override fun add(project: Project, action: Action<in ProjectDependency>) =
        add(project.createDependency(), action)

    // add constraint

    final override fun add(dependencyConstraint: DependencyConstraint) {
        dependencyConstraints.add(dependencyConstraint)
    }

    final override fun add(dependencyConstraint: DependencyConstraint, action: Action<in DependencyConstraint>) {
        action.execute(dependencyConstraint)
        dependencyConstraints.add(dependencyConstraint)
    }

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    final override fun add(dependencyConstraintProvider: Provider<out DependencyConstraint>) =
        dependencyConstraints.add(dependencyConstraintProvider)

    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("addConstraint")
    final override fun add(
        dependencyConstraintProvider: Provider<out DependencyConstraint>,
        action: Action<in DependencyConstraint>,
    ) = dependencyConstraints.add(dependencyConstraintProvider.map { action.execute(it); it })
}
