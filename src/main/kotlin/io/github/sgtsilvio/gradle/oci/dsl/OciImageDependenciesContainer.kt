package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependenciesContainer : Named {

//    val dependencies: NamedDomainObjectContainer<OciImageDependencies>
    val configurations: Provider<List<Configuration>>
    val default: OciImageDependencies

    fun scope(scope: String): OciImageDependencies

    operator fun OciImageDependencies.invoke(dependency: ModuleDependency) = add(dependency)
    operator fun <D : ModuleDependency> OciImageDependencies.invoke(dependency: D, configuration: Action<in D>) = add(dependency, configuration)
    operator fun OciImageDependencies.invoke(dependencyProvider: Provider<out ModuleDependency>) = add(dependencyProvider)
    operator fun <D : ModuleDependency> OciImageDependencies.invoke(dependencyProvider: Provider<out D>, configuration: Action<in D>) = add(dependencyProvider, configuration)

    operator fun OciImageDependencies.invoke(dependencyNotation: CharSequence) = add(dependencyNotation)
    operator fun OciImageDependencies.invoke(dependencyNotation: CharSequence, configuration: Action<in ExternalModuleDependency>) = add(dependencyNotation, configuration)
    operator fun OciImageDependencies.invoke(project: Project) = add(project)
    operator fun OciImageDependencies.invoke(project: Project, configuration: Action<in ProjectDependency>) = add(project, configuration)
}