package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface ResolvableOciImageDependencies : OciImageDependencies<ResolvableOciImageDependencies.Nameable>, Named {

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
