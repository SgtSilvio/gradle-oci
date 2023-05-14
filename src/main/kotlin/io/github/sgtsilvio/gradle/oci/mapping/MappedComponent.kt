package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.ComponentId
import io.github.sgtsilvio.gradle.oci.component.VersionedCapability
import java.util.*

/**
 * @author Silvio Giebl
 */
class MappedComponent(
    val componentId: ComponentId,
    val variants: Map<String, Variant>,
) {
    class Variant(
        val capabilities: SortedSet<VersionedCapability>,
        val imageName: String,
        val tagName: String,
    )
}