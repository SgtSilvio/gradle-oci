package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies {

    val configuration: Configuration
    val set: DomainObjectSet<ModuleDependency>

    fun add(dependency: ModuleDependency)
    fun <D : ModuleDependency> add(dependency: D, action: Action<in D>)
    fun add(dependencyProvider: Provider<out ModuleDependency>)
    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>)

    fun add(dependencyNotation: CharSequence)
    fun add(dependencyNotation: CharSequence, action: Action<in ExternalModuleDependency>)
    fun add(project: Project)
    fun add(project: Project, action: Action<in ProjectDependency>)
    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>)
    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        action: Action<in ExternalModuleDependency>,
    )

    // TODO constraints
}