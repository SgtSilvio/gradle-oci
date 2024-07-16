package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import java.util.*

/**
 * @author Silvio Giebl
 */
internal class OciImageComponent(
    val componentId: VersionedCoordinates,
    val features: Map<String, Feature>,
) {
    class Feature(
        val capabilities: SortedSet<VersionedCoordinates>,
        val imageReference: OciImageReference,
    ) {
        init {
            require(capabilities.isNotEmpty()) { "capabilities must not be empty" }
        }
    }
}
