package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.SetProperty

/**
 * @author Silvio Giebl
 */
interface OciExtension {
//    val registries: NamedDomainObjectList<Registry>
    val imageDefinitions: NamedDomainObjectContainer<OciImageDefinition>

//    fun registries(configuration: Action<in Registries>)

    fun platform(
        os: String,
        architecture: String,
        variant: String = "",
        osVersion: String = "",
        osFeatures: Set<String> = setOf(),
    ): Platform

    fun platformFilter(configuration: Action<in PlatformFilterBuilder>) : PlatformFilter

    fun PlatformFilter.or(configuration: Action<in PlatformFilterBuilder>) : PlatformFilter

//    interface Registries {
//        fun registry(configuration: Action<in Registry>)
//    }

//    interface Registry : Named {
//        var url: URI
//    }

    interface PlatformFilterBuilder {
        val oses: SetProperty<String>
        val architectures: SetProperty<String>
        val variants: SetProperty<String>
        val osVersions: SetProperty<String>
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
}