package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.Named
import org.gradle.api.provider.Provider
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
interface ResolvableOciImageDependencies : OciImageDependencies<ResolvableOciImageDependencies.Nameable>, Named {

    val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>>

    data class Reference(val name: String?, val tag: String?) : Serializable

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
