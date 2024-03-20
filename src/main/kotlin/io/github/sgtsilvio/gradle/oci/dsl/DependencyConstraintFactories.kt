package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface DependencyConstraintFactories {

    fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint

    fun constraint(project: Project): DependencyConstraint

    fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<DependencyConstraint>

    fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        constraint(dependencyProvider.asProvider())
}