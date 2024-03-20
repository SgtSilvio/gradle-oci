package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.DependencyConstraintFactories
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
abstract class DependencyConstraintFactoriesImpl(
    private val dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactories {

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        dependencyConstraintHandler.create(dependencyConstraintNotation)

    final override fun constraint(project: Project): DependencyConstraint = dependencyConstraintHandler.create(project)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyConstraintHandler.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}
