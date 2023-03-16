package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependencyContainer {

    val dependencies: DomainObjectSet<ModuleDependency>
//    val configuration: Configuration
    val metadataFiles: FileCollection
    val layerDigestFiles: FileCollection

    fun add(dependency: ModuleDependency)
    fun <D : ModuleDependency> add(dependency: D, configuration: Action<in D>)
    fun add(dependencyProvider: Provider<out ModuleDependency>)
    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, configuration: Action<in D>)

    fun module(dependencyNotation: CharSequence): ExternalModuleDependency
    fun module(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<ExternalModuleDependency>

    fun add(dependencyNotation: CharSequence)
    fun add(dependencyNotation: CharSequence, configuration: Action<in ExternalModuleDependency>)
    fun add(project: Project)
    fun add(project: Project, configuration: Action<in ProjectDependency>)
}