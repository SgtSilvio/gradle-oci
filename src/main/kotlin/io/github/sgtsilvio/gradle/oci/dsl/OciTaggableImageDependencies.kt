package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.Named
import org.gradle.api.provider.Provider
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
interface OciTaggableImageDependencies : OciImageDependenciesBase<OciTaggableImageDependencies.Nameable>, Named {

    interface Reference : Serializable {
        val name: String?
        val tag: String?
    }

    interface Taggable {
        fun tag(tag: String)
        fun tag(tagProvider: Provider<String>)
    }

    interface Nameable : Taggable {
        fun name(name: String): Taggable
        fun name(nameProvider: Provider<String>): Taggable
    }

    val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>>
}
