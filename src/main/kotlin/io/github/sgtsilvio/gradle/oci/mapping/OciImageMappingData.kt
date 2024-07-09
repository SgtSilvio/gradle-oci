package io.github.sgtsilvio.gradle.oci.mapping

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
