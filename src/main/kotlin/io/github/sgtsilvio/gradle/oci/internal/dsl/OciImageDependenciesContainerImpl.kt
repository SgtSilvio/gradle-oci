package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesContainer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciImageDependenciesContainerImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
) : OciImageDependenciesContainer {

    final override fun getName() = name

    private val scopeToDependencies = mutableMapOf<String, OciTaggableImageDependenciesImpl>()
    final override val configurations: Provider<List<Configuration>> = providerFactory.provider {
        scopeToDependencies.values.map { it.configuration }
    }
    final override val default = scope("")

    final override fun scope(scope: String) = scopeToDependencies.getOrPut(scope) {
        objectFactory.newInstance<OciTaggableImageDependenciesImpl>(
            name + scope.replaceFirstChar(Char::uppercaseChar),
            "OCI images container '$name', scope '$scope'",
        )
    }

    final override fun tag(imageReference: String) =
        OciTaggableImageDependenciesImpl.TagImpl(providerFactory.provider { imageReference })

    final override fun tag(imageReference: Provider<String>) = OciTaggableImageDependenciesImpl.TagImpl(imageReference)
}
