package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesContainer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
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

    final override fun getName() = name

    private val scopeToDependencies = mutableMapOf<String, OciImageDependenciesImpl>()
    final override val configurations: Provider<List<Configuration>> =
        providerFactory.provider { scopeToDependencies.values.map { it.configuration } }
    final override val default = scope("")

    final override fun scope(scope: String) =
        scopeToDependencies[scope] ?: objectFactory.newInstance<OciImageDependenciesImpl>(createConfiguration(scope))
            .also { scopeToDependencies[scope] = it }

    private fun createConfiguration(scope: String): Configuration =
        configurationContainer.create(createConfigurationName(scope)) {
            description = "OCI images container '$name', scope '$scope'"
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            }
        }

    private fun createConfigurationName(scope: String) = "$name${scope.replaceFirstChar(Char::uppercaseChar)}OciImages"
}