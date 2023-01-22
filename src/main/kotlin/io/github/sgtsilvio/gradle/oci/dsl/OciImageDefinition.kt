package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.*
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.*
import org.gradle.api.tasks.TaskProvider
import java.time.Instant

interface OciImageDefinition : OciExtension.UsableImage, Named {
    val capabilities: Capabilities
    val indexAnnotations: MapProperty<String, String>

    val component: Provider<OciComponent>

    fun capabilities(configuration: Action<in Capabilities>)

    fun allPlatforms(configuration: Action<in BundleScope>)

    fun platformsMatching(platformFilter: PlatformFilter, configuration: Action<in BundleScope>)

    fun specificPlatform(platform: Platform)

    fun specificPlatform(platform: Platform, configuration: Action<in Bundle>)

    interface Capabilities {
        val set: Set<Capability>

        fun add(group: String, name: String)
    }

    interface Bundle {
        val parentImages: ParentImages
        val config: Config
        val layers: Layers

        fun parentImages(configuration: Action<in ParentImages>)
        fun config(configuration: Action<in Config>)
        fun layers(configuration: Action<in Layers>)

        interface ParentImages {
            val dependencies: DomainObjectSet<ModuleDependency>

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

            // FileCollection(Dependency) does not make sense as coordinates/capabilities are required
            // bundle does not make sense as order is very important
            // ProviderConvertible does not make sense as can't find a usage
        }

        interface Config {
            val creationTime: Property<Instant>
            val author: Property<String>
            val user: Property<String>
            val ports: SetProperty<String>
            val environment: MapProperty<String, String>
            val entryPoint: ListProperty<String>
            val arguments: ListProperty<String>
            val volumes: SetProperty<String>
            val workingDirectory: Property<String>
            val stopSignal: Property<String>
            val configAnnotations: MapProperty<String, String>
            val configDescriptorAnnotations: MapProperty<String, String>
            val manifestAnnotations: MapProperty<String, String>
            val manifestDescriptorAnnotations: MapProperty<String, String>
        }

        interface Layers {
            val list: NamedDomainObjectList<Layer>

            fun layer(name: String, configuration: Action<in Layer>)
        }

        interface Layer : Named {
            val metadata: Metadata

            fun metadata(configuration: Action<in Metadata>)

            fun contents(configuration: Action<in OciCopySpec>)

            fun contents(task: TaskProvider<OciLayerTask>)

            interface Metadata {
                val creationTime: Property<Instant>
                val author: Property<String>
                val createdBy: Property<String>
                val comment: Property<String>
                val annotations: MapProperty<String, String>
            }
        }
    }

    interface BundleScope {
//        val layers: LayersScope

        fun parentImages(configuration: Action<in Bundle.ParentImages>)
        fun config(configuration: Action<in Bundle.Config>)
        fun layers(configuration: Action<in LayersScope>)

        interface LayersScope {
//            val list: NamedDomainObjectList<LayerScope>

            fun layer(name: String, configuration: Action<in LayerScope>)
        }

        interface LayerScope : Named {
            fun metadata(configuration: Action<in Bundle.Layer.Metadata>)
            fun contents(configuration: Action<in OciCopySpec>)
            fun contents(task: TaskProvider<OciLayerTask>)
        }
    }
}