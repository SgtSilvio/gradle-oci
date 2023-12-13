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
    private val providerFactory: ProviderFactory,
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

    final override fun named(name: String) =
        OciTaggableImageDependenciesImpl.ReferenceSpecImpl(providerFactory.provider {
            OciTaggableImageDependenciesImpl.ReferenceImpl(name, null)
        })

    final override fun named(nameProvider: Provider<String>) =
        OciTaggableImageDependenciesImpl.ReferenceSpecImpl(nameProvider.map<OciTaggableImageDependencies.Reference> { name ->
            OciTaggableImageDependenciesImpl.ReferenceImpl(name, null)
        }.orElse(OciTaggableImageDependenciesImpl.ReferenceImpl.DEFAULT))

    final override fun tagged(tag: String) =
        OciTaggableImageDependenciesImpl.ReferenceSpecImpl(providerFactory.provider {
            OciTaggableImageDependenciesImpl.ReferenceImpl(null, tag)
        })

    final override fun tagged(tagProvider: Provider<String>) =
        OciTaggableImageDependenciesImpl.ReferenceSpecImpl(tagProvider.map<OciTaggableImageDependencies.Reference> { tag ->
            OciTaggableImageDependenciesImpl.ReferenceImpl(null, tag)
        }.orElse(OciTaggableImageDependenciesImpl.ReferenceImpl.DEFAULT))
}
