package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.Named
import org.gradle.api.provider.Provider
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
interface OciTaggableImageDependencies : OciImageDependencies<OciTaggableImageDependencies.Nameable>, Named {

    val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>>

    data class Reference(val name: String?, val tag: String?) : Serializable

    interface Taggable {
        fun tag(tag: String)
        fun tag(tagProvider: Provider<String>)
    }

    interface Nameable : Taggable {
        fun name(name: String): Taggable
        fun name(nameProvider: Provider<String>): Taggable
    }
}
