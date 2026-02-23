package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.layer.OciLayerTask
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.*
import org.gradle.api.tasks.TaskProvider
import java.time.Instant

interface OciImageDefinition : Named, CapabilityFactories {
    val imageName: Property<String>
    val imageTag: Property<String>
    val capabilities: SetProperty<Capability>
    val indexAnnotations: MapProperty<String, String>

    val dependency: Provider<ProjectDependency>

    fun allPlatforms(configuration: Action<in VariantScope>)

    fun platformsMatching(platformFilter: PlatformFilter, configuration: Action<in VariantScope>)

    fun specificPlatform(platform: Platform)

    fun specificPlatform(platform: Platform, configuration: Action<in Variant>)

    interface Variant {
        val dependencies: ParentOciImageDependencies
        val config: Config
        val layers: NamedDomainObjectList<Layer>

        fun dependencies(configuration: Action<in ParentOciImageDependencies>)

        fun config(configuration: Action<in Config>)

        fun layer(name: String, configuration: Action<in Layer>)

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

        interface Layer : Named {
            val metadata: Metadata

            fun metadata(configuration: Action<in Metadata>)

            fun contents(configuration: Action<in OciCopySpec>)

            fun contents(task: TaskProvider<out OciLayerTask>)

            interface Metadata {
                val creationTime: Property<Instant>
                val author: Property<String>
                val createdBy: Property<String>
                val comment: Property<String>
                val annotations: MapProperty<String, String>
            }
        }
    }

    interface VariantScope {
        val layers: NamedDomainObjectList<Layer>

        fun dependencies(configuration: Action<in ParentOciImageDependencies>)
        fun config(configuration: Action<in Variant.Config>)
        fun layer(name: String, configuration: Action<in Layer>)

        interface Layer : Named {
            fun metadata(configuration: Action<in Variant.Layer.Metadata>)
            fun contents(configuration: Action<in OciCopySpec>)
            fun contents(task: TaskProvider<out OciLayerTask>)
        }
    }
}
