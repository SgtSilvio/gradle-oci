package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
class OciImageNameMappingData(
    val groupMappings: Map<String, ComponentSpec>,
    val moduleMappings: Map<Pair<String, String>, ComponentSpec>,
    val componentMappings: Map<Triple<String, String, String>, ComponentSpec>,
) {

    class ComponentSpec(val mainVariant: VariantSpec, val featureVariants: Map<String, VariantSpec>)

    class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val tagName: NameSpec?,
    )
}
