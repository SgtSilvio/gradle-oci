package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.ComponentId
import io.github.sgtsilvio.gradle.oci.component.ModuleId
import java.util.*

/**
 * @author Silvio Giebl
 */
class OciImageNameMappingData(
    val groupMappings: SortedMap<String, ComponentSpec>,
    val moduleMappings: SortedMap<ModuleId, ComponentSpec>,
    val componentMappings: SortedMap<ComponentId, ComponentSpec>,
) {

    class ComponentSpec(val mainVariant: VariantSpec, val featureVariants: SortedMap<String, VariantSpec>)

    class VariantSpec(
        val capabilities: List<Triple<NameSpec, NameSpec, NameSpec>>,
        val imageName: NameSpec?,
        val tagName: NameSpec?,
    )
}
