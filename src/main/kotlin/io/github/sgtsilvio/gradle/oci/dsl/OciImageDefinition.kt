package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.*
import org.gradle.api.tasks.TaskProvider
import java.time.Instant

interface OciImageDefinition : Named {
    val imageName: Property<String>
    val imageTag: Property<String>
    val capabilities: Capabilities
    val indexAnnotations: MapProperty<String, String>

    val dependency: Provider<ProjectDependency>

    fun capabilities(configuration: Action<in Capabilities>)

    fun allPlatforms(configuration: Action<in VariantScope>)

    fun platformsMatching(platformFilter: PlatformFilter, configuration: Action<in VariantScope>)

    fun specificPlatform(platform: Platform)

    fun specificPlatform(platform: Platform, configuration: Action<in Variant>)

    interface Capabilities {
        val set: Provider<Set<Capability>>

        fun add(notation: String)

        fun add(notationProvider: Provider<String>)

        operator fun plusAssign(notation: String) = add(notation)

        operator fun plusAssign(notationProvider: Provider<String>) = add(notationProvider)
    }

    interface Variant {
        val parentImages: ParentImages
        val config: Config
        val layers: Layers

        fun parentImages(configuration: Action<in ParentImages>)
        fun config(configuration: Action<in Config>)
        fun layers(configuration: Action<in Layers>)

        interface ParentImages : OciImageDependencies<Unit>

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

    interface VariantScope {
        val layers: Layers

        fun parentImages(configuration: Action<in Variant.ParentImages>)
        fun config(configuration: Action<in Variant.Config>)
        fun layers(configuration: Action<in Layers>)

        interface Layers {
            val list: NamedDomainObjectList<Layer>

            fun layer(name: String, configuration: Action<in Layer>)
        }

        interface Layer : Named {
            fun metadata(configuration: Action<in Variant.Layer.Metadata>)
            fun contents(configuration: Action<in OciCopySpec>)
            fun contents(task: TaskProvider<OciLayerTask>)
        }
    }
}
