package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependenciesContainer : Named {

    val scopes: Provider<List<OciTaggableImageDependencies>>
    val default: OciTaggableImageDependencies

    fun scope(scope: String): OciTaggableImageDependencies

    fun named(name: String): OciTaggableImageDependencies.NamedReferenceSpec

    fun named(nameProvider: Provider<String>): OciTaggableImageDependencies.NamedReferenceSpec

    fun tagged(tag: String): OciTaggableImageDependencies.ReferenceSpec

    fun tagged(tagProvider: Provider<String>): OciTaggableImageDependencies.ReferenceSpec
}