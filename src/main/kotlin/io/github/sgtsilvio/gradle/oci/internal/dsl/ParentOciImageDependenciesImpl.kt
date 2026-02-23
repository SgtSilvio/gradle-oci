package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.dsl.ParentOciImageDependencies
import io.github.sgtsilvio.gradle.oci.image.OciVariantInput
import io.github.sgtsilvio.gradle.oci.internal.resolution.resolveOciVariantInputs
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class ParentOciImageDependenciesImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    private val configurationContainer: ConfigurationContainer,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), ParentOciImageDependencies {

    final override fun getName() = name

    final override val runtime = objectFactory.newInstance<OciImageDependencyCollectorImpl.Default>()

    private val platformConfigurations = HashMap<String, Configuration>()

    private fun getOrCreatePlatformConfiguration(platform: Platform): Configuration {
        val platformConfigurationName = "${name}ParentOciImages@$platform"
        return platformConfigurations.getOrPut(platformConfigurationName) {
            configurationContainer.create(platformConfigurationName).apply {
                description = "Parent OCI image dependencies '$name' for the platform $platform."
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes.apply {
                    attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                    attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                    attribute(PLATFORM_ATTRIBUTE, platform.toString())
                }
                dependencies.addAllLater(runtime.dependencies)
                dependencyConstraints.addAllLater(runtime.dependencyConstraints)
            }
        }
    }

    final override fun resolve(platformProvider: Provider<Platform>): Provider<List<OciVariantInput>> =
        platformProvider.flatMap { platform ->
            val configuration = getOrCreatePlatformConfiguration(platform)
            resolveOciVariantInputs(configuration.incoming)
        }
}
