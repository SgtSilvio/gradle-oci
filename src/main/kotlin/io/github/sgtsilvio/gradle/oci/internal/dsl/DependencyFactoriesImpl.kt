package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.DependencyConstraintFactories
import io.github.sgtsilvio.gradle.oci.dsl.DependencyFactories
import io.github.sgtsilvio.gradle.oci.internal.gradle.createProjectDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
abstract class DependencyFactoriesImpl(
    private val project: Project,
) : DependencyFactories, DependencyConstraintFactories {

    final override fun module(dependencyNotation: CharSequence) = project.dependencyFactory.create(dependencyNotation)

    final override fun project() = project.createProjectDependency()

    final override fun project(projectPath: String) = project.createProjectDependency(projectPath)

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        project.dependencies.constraints.create(dependencyConstraintNotation)

    final override fun constraint(projectDependency: ProjectDependency): DependencyConstraint =
        project.dependencies.constraints.create(projectDependency)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        project.dependencies.constraints.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}
