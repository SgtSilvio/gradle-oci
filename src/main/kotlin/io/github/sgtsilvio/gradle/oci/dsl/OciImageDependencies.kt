package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.image.OciImageInput
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies : DependencyConstraintFactories, Named {

    val runtime: OciImageDependencyCollector

    fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImageInput>>
}

interface OciImageDependencyCollector : OciImageDependencyCollectorBase<OciImageDependencyCollector.Nameable> {

    interface Taggable {
        fun tag(vararg tags: String): Taggable
        fun tag(tags: Iterable<String>): Taggable
        fun tag(tagProvider: Provider<String>): Taggable

        @Suppress("INAPPLICABLE_JVM_NAME")
        @JvmName("tagMultiple")
        fun tag(tagsProvider: Provider<out Iterable<String>>): Taggable
    }

    interface Nameable : Taggable {
        fun name(name: String): Taggable
        fun name(nameProvider: Provider<String>): Taggable
    }
}
