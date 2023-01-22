package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
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
abstract class OciExtensionImpl @Inject constructor(private val objectFactory: ObjectFactory) : OciExtension {

    final override val imageDefinitions = objectFactory.domainObjectContainer(OciImageDefinition::class) { name ->
        objectFactory.newInstance<OciImageDefinitionImpl>(name)
    }

    init {
        // eagerly realize imageDefinitions because it registers configurations and tasks
        imageDefinitions.all {}
    }

    override fun platform(
        os: String,
        architecture: String,
        variant: String,
        osVersion: String,
        osFeatures: Set<String>,
    ) = PlatformImpl(os, architecture, variant, osVersion, osFeatures.toSortedSet())

    override fun platformFilter(configuration: Action<in OciExtension.PlatformFilterBuilder>): PlatformFilter {
        val builder = objectFactory.newInstance<OciExtension.PlatformFilterBuilder>()
        configuration.execute(builder)
        return PlatformFilter(
            builder.oses.get(),
            builder.architectures.get(),
            builder.variants.get(),
            builder.osVersions.get(),
        )
    }

    override fun PlatformFilter.or(configuration: Action<in OciExtension.PlatformFilterBuilder>) =
        or(platformFilter(configuration))
}