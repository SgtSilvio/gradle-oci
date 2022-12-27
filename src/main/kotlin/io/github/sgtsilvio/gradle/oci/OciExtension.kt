package io.github.sgtsilvio.gradle.oci

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.*
import org.gradle.api.specs.Spec
import java.net.URI
import java.time.Instant

/**
 * @author Silvio Giebl
 */
interface OciExtension {
    val registries: NamedDomainObjectList<Registry>
    val imageDefinitions: NamedDomainObjectContainer<ImageDefinition>

    fun registries(configuration: Action<in Registries>)

    fun platform(
        os: String,
        architecture: String,
        variant: String? = null,
        osVersion: String? = null,
        osFeatures: List<String> = listOf(),
    ): Platform

    interface Registries {
        fun registry(configuration: Action<in Registry>)
    }

    interface Registry : Named {
        var url: URI
    }

    interface Platform {
        val os: String
        val architecture: String
        val variant: String?
        val osVersion: String?
        val osFeatures: List<String>
    }

    interface Capability {
        val group: String
        val name: String
    }

    interface Image {
        val capabilities: Provider<Set<Capability>>
        val componentFiles: FileCollection
        val layerFiles: FileCollection
    }

    interface UsableImage : Image {
        val digestToMetadataPropertiesFile: Provider<RegularFile>
        val digestToLayerPathPropertiesFile: Provider<RegularFile>
    }

    interface ImageDefinition : UsableImage, Named {
        override val capabilities: SetProperty<Capability>
        val indexAnnotations: MapProperty<String, String>

        fun allPlatforms(configuration: Action<in Bundle>)

        fun addPlatform(platform: Platform)

        fun addPlatform(platform: Platform, configuration: Action<in Bundle>)

        fun platformsMatching(spec: Spec<in Platform>, configuration: Action<in Bundle>)

        interface Bundle {
            val parentImages: ParentImages

            val creationTime: Property<Instant>
            val author: Property<String>
            val user: Property<String>
            val ports: SetProperty<String>
            val environment: MapProperty<String, String>
            val entryPoint: ListProperty<String> // convention null
            val arguments: ListProperty<String> // convention null
            val volumes: SetProperty<String>
            val workingDirectory: Property<String>
            val stopSignal: Property<String>
            val configAnnotations: MapProperty<String, String>
            val configDescriptorAnnotations: MapProperty<String, String>
            val manifestAnnotations: MapProperty<String, String>
            val manifestDescriptorAnnotations: MapProperty<String, String>

            val layers: NamedDomainObjectList<Layer>

            fun parentImages(configuration: Action<in ParentImages>)

            fun layers(configuration: Action<in Layers>)

            interface ParentImages {
                fun add(dependency: ModuleDependency)
                fun <D : ModuleDependency> add(dependency: D, configuration: Action<in D>)
                fun add(dependencyProvider: Provider<out ModuleDependency>)
                fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, configuration: Action<in D>)

                fun module(dependencyNotation: CharSequence): ExternalModuleDependency
                fun module(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<ExternalModuleDependency>
                fun project(): ProjectDependency
                fun project(projectPath: String): ProjectDependency

                fun add(dependencyNotation: CharSequence) = add(module(dependencyNotation))
                fun add(dependencyNotation: CharSequence, configuration: Action<in ExternalModuleDependency>) =
                    add(module(dependencyNotation), configuration)

                // FileCollection(Dependency) does not make sense as coordinates/capabilities are required
                // bundle does not make sense as order is very important
                // ProviderConvertible does not make sense as can't find a usage
            }

            interface Layers {
                fun layer(name: String, configuration: Action<in Layer>)
            }

            interface Layer : Named {
                val creationTime: Property<Instant>
                val author: Property<String>
                val createdBy: Property<String>
                val comment: Property<String>
                val annotations: MapProperty<String, String>

                fun contents(configuration: Action<in OciCopySpec>)

                fun contents(task: OciLayerTask)
            }
        }
    }
}