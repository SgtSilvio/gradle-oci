package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.OciSettingsExtension
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import org.gradle.api.Action
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciSettingsExtensionImpl @Inject constructor(
    repositoryHandler: RepositoryHandler,
    providerFactory: ProviderFactory,
    objectFactory: ObjectFactory,
    buildServiceRegistry: BuildServiceRegistry,
) : OciSettingsExtension {

    final override val registries = objectFactory.newInstance<OciRegistriesImpl>(repositoryHandler, providerFactory)

    final override val imageMapping = objectFactory.newInstance<OciImageMappingImpl>()

    init {
        setupSettingsOciRegistries(buildServiceRegistry, registries, imageMapping)
    }

    final override fun registries(configuration: Action<in OciRegistries>) = configuration.execute(registries)

    final override fun imageMapping(configuration: Action<in OciImageMapping>) = configuration.execute(imageMapping)
}
