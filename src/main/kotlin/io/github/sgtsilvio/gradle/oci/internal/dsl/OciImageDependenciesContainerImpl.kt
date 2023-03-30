package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesContainer
import io.github.sgtsilvio.gradle.oci.internal.DISTRIBUTION_TYPE_ATTRIBUTE
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciImageDependenciesContainerImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    private val configurationContainer: ConfigurationContainer,
) : OciImageDependenciesContainer {

    override fun getName() = name

    private val configurationList = mutableListOf<Configuration>()
    final override val configurations: Provider<List<Configuration>> = providerFactory.provider { configurationList }
    final override val default = scope("")

    final override fun scope(scope: String) =
        objectFactory.newInstance<OciImageDependenciesImpl>(getOrCreateConfiguration(scope))

    private fun getOrCreateConfiguration(scope: String): Configuration {
        val configurationName = createConfigurationName(scope)
        if (configurationName in configurationContainer.names) {
            return configurationContainer[configurationName]
        }
        val configuration = configurationContainer.create(configurationName) {
            description = "OCI images container '$name', scope '$scope'"
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named("distribution"))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named("oci-image"))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            }
        }
        configurationList += configuration
        return configuration
    }

    private fun createConfigurationName(scope: String) = "$name${scope.replaceFirstChar(Char::uppercaseChar)}OciImages"
}