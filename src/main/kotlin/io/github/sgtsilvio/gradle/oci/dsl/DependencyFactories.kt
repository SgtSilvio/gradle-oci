package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface DependencyFactories {

    fun project(): ProjectDependency

    fun project(projectPath: String): ProjectDependency
}

interface DependencyConstraintFactories {

    fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint

    fun constraint(projectDependency: ProjectDependency): DependencyConstraint

    fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<DependencyConstraint>

    fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        constraint(dependencyProvider.asProvider())
}
