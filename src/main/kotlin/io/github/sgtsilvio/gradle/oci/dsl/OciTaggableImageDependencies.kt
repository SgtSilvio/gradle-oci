package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

/**
 * @author Silvio Giebl
 */
interface OciTaggableImageDependencies : OciImageDependencies {

    fun add(dependency: ModuleDependency, imageReference: String)
    fun add(dependency: ModuleDependency, imageReferenceProvider: Provider<String>)
    fun <D : ModuleDependency> add(dependency: D, imageReference: String, action: Action<in D>)
    fun <D : ModuleDependency> add(dependency: D, imageReferenceProvider: Provider<String>, action: Action<in D>)
    fun add(dependencyProvider: Provider<out ModuleDependency>, imageReference: String)
    fun add(dependencyProvider: Provider<out ModuleDependency>, imageReferenceProvider: Provider<String>)
    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, imageReference: String, action: Action<in D>)
    fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, imageReferenceProvider: Provider<String>, action: Action<in D>)

    fun add(dependencyNotation: CharSequence, imageReference: String)
    fun add(dependencyNotation: CharSequence, imageReferenceProvider: Provider<String>)
    fun add(dependencyNotation: CharSequence, imageReference: String, action: Action<in ExternalModuleDependency>)
    fun add(dependencyNotation: CharSequence, imageReferenceProvider: Provider<String>, action: Action<in ExternalModuleDependency>)
    fun add(project: Project, imageReference: String)
    fun add(project: Project, imageReferenceProvider: Provider<String>)
    fun add(project: Project, imageReference: String, action: Action<in ProjectDependency>)
    fun add(project: Project, imageReferenceProvider: Provider<String>, action: Action<in ProjectDependency>)
    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, imageReference: String)
    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, imageReferenceProvider: Provider<String>)
    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, imageReference: String, action: Action<in ExternalModuleDependency>)
    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, imageReferenceProvider: Provider<String>, action: Action<in ExternalModuleDependency>)
}