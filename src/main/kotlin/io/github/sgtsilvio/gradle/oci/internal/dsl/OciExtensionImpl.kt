package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.internal.copyspec.OciCopySpecImpl
import io.github.sgtsilvio.gradle.oci.internal.copyspec.newOciCopySpec
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.domainObjectContainer
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciExtensionImpl @Inject constructor(private val objectFactory: ObjectFactory) : OciExtension {

    final override val imageMapping = objectFactory.newInstance<OciImageMappingImpl>()

    final override val registries = objectFactory.newInstance<OciRegistriesImpl>(imageMapping)

    final override val imageDefinitions = objectFactory.domainObjectContainer(OciImageDefinition::class) { name ->
        objectFactory.newInstance<OciImageDefinitionImpl>(name)
    }

    final override val imageDependencies =
        objectFactory.domainObjectContainer(ResolvableOciImageDependencies::class) { name ->
            objectFactory.newInstance<ResolvableOciImageDependenciesImpl>(name)
        }

    init {
        // eagerly realize imageDefinitions because they register configurations and tasks
        imageDefinitions.all {}
        // eagerly realize imageDependencies because they register configurations
        imageDependencies.all {}
    }

    final override fun registries(configuration: Action<in OciRegistries>) = configuration.execute(registries)

    final override fun imageMapping(configuration: Action<in OciImageMapping>) = configuration.execute(imageMapping)

    final override fun platform(
        os: String,
        architecture: String,
        variant: String,
        osVersion: String,
        osFeatures: Set<String>,
    ): Platform = PlatformImpl(os, architecture, variant, osVersion, osFeatures.toSortedSet())

    final override fun platformFilter(configuration: Action<in OciExtension.PlatformFilterBuilder>): PlatformFilter {
        val builder = objectFactory.newInstance<OciExtension.PlatformFilterBuilder>()
        configuration.execute(builder)
        return PlatformFilter(
            builder.oses.get(),
            builder.architectures.get(),
            builder.variants.get(),
            builder.osVersions.get(),
        )
    }

    final override fun PlatformFilter.or(configuration: Action<in OciExtension.PlatformFilterBuilder>) =
        or(platformFilter(configuration))

    final override fun copySpec() = objectFactory.newOciCopySpec()

    final override fun copySpec(configuration: Action<in OciCopySpec>): OciCopySpecImpl {
        val copySpec = copySpec()
        configuration.execute(copySpec)
        return copySpec
    }
}
