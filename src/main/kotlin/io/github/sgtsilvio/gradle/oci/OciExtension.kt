package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.model.OciMultiPlatformImage
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.provider.*
import org.gradle.api.tasks.TaskProvider
import java.time.Instant
import kotlin.reflect.KClass

/**
 * @author Silvio Giebl
 */
interface OciExtension {
    val registries: NamedDomainObjectList<Registry>
    val imageDefinitions: NamedDomainObjectContainer<ImageDefinition>

    val indexTaskClass: KClass<out OciIndexTask>
    val manifestTaskClass: KClass<out OciManifestTask>
    val configTaskClass: KClass<out OciConfigTask>
    val layerTaskClass: KClass<out OciLayerTask>

    fun registries(action: Action<in Registries>)

    interface Registries {
        fun registry(action: Action<in Registry>)
    }

    interface Registry : Named {

    }

    interface ImageDefinition : Named {
        val baseImage: Property<OciMultiPlatformImage>
        val platforms: Provider<List<Platform>> // default to all platforms of base image? // TODO Set
        val config: Config
        val layers: NamedDomainObjectList<Layer>
        val image: OciMultiPlatformImage

        val indexTask: TaskProvider<OciIndexTask>

        fun platforms(action: Action<in Platforms>)

        fun config(action: Action<in Config>)

        fun layers(action: Action<in Layers>)

        interface Platforms {
            fun platform(action: Action<in Platform>)
        }

        interface Platform {
            val architecture: Property<String>
            val os: Property<String>
            val osVersion: Property<String>
            val osFeatures: ListProperty<String>
            val variant: Property<String>

            val manifestTask: TaskProvider<OciManifestTask>
            val configTask: TaskProvider<OciConfigTask>
        }

        interface Config {
            val creationTime: Property<Instant> // convention Instant.EPOCH
            val author: Property<String>
            val user: Property<String> // default from baseImage, can not be a convention because config may differ for different platforms
            val ports: SetProperty<String> // prefilled from baseImage
            val environment: MapProperty<String, String> // prefilled from baseImage
            val entryPoint: ListProperty<String> // default from baseImage
            val arguments: ListProperty<String> // default from baseImage if entryPoint is not set (entryPoint.map(x->empty).convention(baseImage.arguments) => does not work if we use convention because entryPoint is always set)
            val volumes: SetProperty<String> // prefilled from baseImage
            val workingDirectory: Property<String> // default from baseImage
            val stopSignal: Property<String>
            val annotations: MapProperty<String, String> // prefilled from baseImage
        }

        interface Layers {
            fun layer(action: Action<in Layer>)

            fun layersFromComponent(notation: String)
        }

        interface Layer : Named {
            val layerTask: TaskProvider<OciLayerTask>

            fun contents(action: Action<in OciCopySpec>)
        }
    }
}