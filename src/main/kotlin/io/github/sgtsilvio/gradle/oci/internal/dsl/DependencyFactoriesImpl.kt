package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.DependencyConstraintFactories
import io.github.sgtsilvio.gradle.oci.dsl.DependencyFactories
import io.github.sgtsilvio.gradle.oci.internal.gradle.createProjectDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
abstract class DependencyFactoriesImpl(
    private val project: Project,
    private val dependencyHandler: DependencyHandler,
) : DependencyFactories, DependencyConstraintFactories {

    final override fun project() = project.createProjectDependency()

    final override fun project(projectPath: String) = dependencyHandler.createProjectDependency(projectPath)

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        dependencyHandler.constraints.create(dependencyConstraintNotation)

    final override fun constraint(projectDependency: ProjectDependency): DependencyConstraint =
        dependencyHandler.constraints.create(projectDependency)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyHandler.constraints.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}
