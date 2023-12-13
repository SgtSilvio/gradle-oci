package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import java.util.*

/**
 * @author Silvio Giebl
 */
class MappedComponent(
    val componentId: VersionedCoordinates,
    val variants: Map<String, Variant>,
) {
    class Variant(
        val capabilities: SortedSet<VersionedCoordinates>,
        val imageReference: OciImageReference,
    )
}
