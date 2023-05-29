package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
class OciImageNameMappingData(
    val groupMappings: Map<String, ComponentSpec>,
    val moduleMappings: Map<Pair<String, String>, ComponentSpec>,
    val componentMappings: Map<Triple<String, String, String>, ComponentSpec>,
) {

    sealed class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val tagName: NameSpec?,
    )

    class ComponentSpec(
        capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        imageName: NameSpec?,
        tagName: NameSpec?,
        val featureVariants: List<FeatureVariantSpec>,
    ) : VariantSpec(capabilities, imageName, tagName)

    class FeatureVariantSpec(
        val name: String,
        capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        imageName: NameSpec?,
        tagName: NameSpec?,
    ) : VariantSpec(capabilities, imageName, tagName)
}
