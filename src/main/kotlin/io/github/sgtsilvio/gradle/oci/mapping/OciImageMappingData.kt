package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates

/**
 * @author Silvio Giebl
 */
internal class OciImageMappingData(
    val groupMappings: Map<String, ComponentSpec>,
    val moduleMappings: Map<Coordinates, ComponentSpec>,
    val componentMappings: Map<VersionedCoordinates, ComponentSpec>,
) {

    class ComponentSpec(val mainVariant: VariantSpec, val featureVariants: Map<String, VariantSpec>)

    class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val imageTag: NameSpec?,
    )
}
