package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
internal class OciImageMappingData(
    val groupMappings: Map<String, ComponentSpec>,
    val moduleMappings: Map<Coordinates, ComponentSpec>,
    val componentMappings: Map<VersionedCoordinates, ComponentSpec>,
) {

    class ComponentSpec(val mainFeature: FeatureSpec, val additionalFeatures: Map<String, FeatureSpec>)

    class FeatureSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val imageTag: NameSpec?,
    )
}
