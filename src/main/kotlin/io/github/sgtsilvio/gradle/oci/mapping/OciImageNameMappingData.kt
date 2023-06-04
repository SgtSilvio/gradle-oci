package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import java.util.*

/**
 * @author Silvio Giebl
 */
class OciImageNameMappingData(
    val groupMappings: SortedMap<String, ComponentSpec>,
    val moduleMappings: SortedMap<Coordinates, ComponentSpec>,
    val componentMappings: SortedMap<VersionedCoordinates, ComponentSpec>,
) {

    class ComponentSpec(val mainVariant: VariantSpec, val featureVariants: SortedMap<String, VariantSpec>)

    class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val imageTag: NameSpec?,
    )
}
