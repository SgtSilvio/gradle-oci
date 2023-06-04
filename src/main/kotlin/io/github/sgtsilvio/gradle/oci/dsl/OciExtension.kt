package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciExtension {
    val registries: OciRegistries
    val imageMapping: OciImageMapping
    val imageDefinitions: NamedDomainObjectContainer<OciImageDefinition>
    val imageDependencies: NamedDomainObjectContainer<OciImageDependenciesContainer>

    fun registries(configuration: Action<in OciRegistries>)

    fun imageMapping(configuration: Action<in OciImageMapping>)

    fun platform(
        os: String,
        architecture: String,
        variant: String = "",
        osVersion: String = "",
        osFeatures: Set<String> = setOf(),
    ): Platform

    fun platformFilter(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    fun PlatformFilter.or(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    interface PlatformFilterBuilder {
        val oses: SetProperty<String>
        val architectures: SetProperty<String>
        val variants: SetProperty<String>
        val osVersions: SetProperty<String>
    }

    fun NamedDomainObjectContainer<OciImageDependenciesContainer>.forTest(
        testTask: TaskProvider<Test>,
        action: Action<in OciImageDependenciesContainer>,
    )
}