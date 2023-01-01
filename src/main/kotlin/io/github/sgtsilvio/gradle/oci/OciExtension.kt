package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import org.gradle.api.*
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.*
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import java.time.Instant

/**
 * @author Silvio Giebl
 */
interface OciExtension {
//    val registries: NamedDomainObjectList<Registry>
    val imageDefinitions: NamedDomainObjectContainer<ImageDefinition>

//    fun registries(configuration: Action<in Registries>)

    fun platform(
        os: String,
        architecture: String,
        variant: String? = null,
        osVersion: String? = null,
        osFeatures: Set<String> = setOf(),
    ): Platform

//    interface Registries {
//        fun registry(configuration: Action<in Registry>)
//    }

//    interface Registry : Named {
//        var url: URI
//    }

    interface Platform {
        val os: String
        val architecture: String
        val variant: String?
        val osVersion: String?
        val osFeatures: Set<String>
    }

    interface Image {
//        val capabilities: Set<Capability>
//        val componentFiles: FileCollection
//        val layerFiles: FileCollection
    }

    interface UsableImage : Image {
//        val digestToMetadataPropertiesFile: Provider<RegularFile>
//        val digestToLayerPathPropertiesFile: Provider<RegularFile>
    }

    interface ImageDefinition : UsableImage, Named {
        val capabilities: Capabilities
        val indexAnnotations: MapProperty<String, String>

        val component: Provider<OciComponent>

        fun capabilities(configuration: Action<in Capabilities>)

        fun allPlatforms(configuration: Action<in Bundle>) // TODO create layers not for every platform if same config
        // decorate Bundle so that layers is also decorated (different layers set for this and the other methods)
        // layers only executed for the first bundle

        fun addPlatform(platform: Platform) // TODO addPlatform + existingPlatform or general method platform (putIfAbsent)

        fun addPlatform(platform: Platform, configuration: Action<in Bundle>)

        fun platformsMatching(spec: Spec<in Platform>, configuration: Action<in Bundle>) // TODO same as for allPlatforms

        interface Capabilities {
            val set: Set<Capability>

            fun capability(group: String, name: String) // TODO maybe rename to add, capability should return a capability (but this method is not needed probably), parentImages.add is also not called parentImages.parentImage
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

                // TODO what to do if name already exists, if throws then no modification can happen (but still via layers field if not replaced with layerMetadata)
                // TODO can not throw if executed in a different scope (once per allPlatforms for example)
                fun layer(name: String, configuration: Action<in Layer>)
            }

            interface Layer : Named {
                val metadata: Metadata

                fun metadata(configuration: Action<in Metadata>)

                // TODO needs to be executed in a different scope, only once for a allPlatforms call for example
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
    }
}