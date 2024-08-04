package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.*
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.SetProperty

/**
 * @author Silvio Giebl
 */
interface OciExtension {
    val layerTaskClass get() = OciLayerTask::class
    val imagesTaskClass get() = OciImagesTask::class
    val pushTaskClass get() = OciPushTask::class
    val pushSingleTaskClass get() = OciPushSingleTask::class
    val registryDataTaskClass get() = OciRegistryDataTask::class

    val registries: OciRegistries
    val imageMapping: OciImageMapping
    val imageDefinitions: NamedDomainObjectContainer<OciImageDefinition>
    val imageDependencies: NamedDomainObjectContainer<ReferencableOciImageDependencyCollector>

    fun registries(configuration: Action<in OciRegistries>)

    fun imageMapping(configuration: Action<in OciImageMapping>)

    fun platform(
        os: String,
        architecture: String,
        variant: String = "",
        osVersion: String = "",
        osFeatures: Set<String> = emptySet(),
    ): Platform

    fun platformFilter(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    fun PlatformFilter.or(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    interface PlatformFilterBuilder {
        val oses: SetProperty<String>
        val architectures: SetProperty<String>
        val variants: SetProperty<String>
        val osVersions: SetProperty<String>
    }

    fun platformSelector(platform: Platform): PlatformSelector

    fun copySpec(): OciCopySpec

    fun copySpec(configuration: Action<in OciCopySpec>): OciCopySpec
}
