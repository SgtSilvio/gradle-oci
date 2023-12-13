package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesContainer
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
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
    providerFactory: ProviderFactory,
) : OciImageDependenciesContainer {

    final override fun getName() = name

    private val scopeToDependencies = mutableMapOf<String, OciTaggableImageDependenciesImpl>()
    final override val scopes: Provider<List<OciTaggableImageDependencies>> = providerFactory.provider {
        scopeToDependencies.values.toList()
    }
    final override val default = scope("")

    final override fun scope(scope: String) = scopeToDependencies.getOrPut(scope) {
        objectFactory.newInstance<OciTaggableImageDependenciesImpl>(
            name + scope.replaceFirstChar(Char::uppercaseChar),
            "OCI images container '$name', scope '$scope'",
        )
    }
}
