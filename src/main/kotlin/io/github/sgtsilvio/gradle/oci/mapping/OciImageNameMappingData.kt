package io.github.sgtsilvio.gradle.oci.mapping

import java.util.*

/**
 * @author Silvio Giebl
 */
class OciImageNameMappingData(
    val groupMappings: SortedMap<String, ComponentSpec>,
    val moduleMappings: SortedMap<Pair<String, String>, ComponentSpec>,
    val componentMappings: SortedMap<Triple<String, String, String>, ComponentSpec>,
) {

    class ComponentSpec(val mainVariant: VariantSpec, val featureVariants: SortedMap<String, VariantSpec>)

    class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val tagName: NameSpec?,
    )
}
