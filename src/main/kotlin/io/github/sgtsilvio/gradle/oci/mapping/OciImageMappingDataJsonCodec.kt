package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.decodeCoordinates
import io.github.sgtsilvio.gradle.oci.component.decodeVersionedCoordinates
import io.github.sgtsilvio.gradle.oci.component.encodeCoordinates
import io.github.sgtsilvio.gradle.oci.component.encodeVersionedCoordinates
import io.github.sgtsilvio.gradle.oci.internal.json.*
import java.util.*

fun OciImageMappingData.encodeToJsonString() = jsonObject { encodeOciImageNameMappingData(this@encodeToJsonString) }

fun JsonObjectStringBuilder.encodeOciImageNameMappingData(data: OciImageMappingData) {
    addArrayIfNotEmpty("groupMappings", data.groupMappings.entries) { (group, componentSpec) ->
        addObject {
            addString("group", group)
            encodeComponentSpec(componentSpec)
        }
    }
    addArrayIfNotEmpty("moduleMappings", data.moduleMappings.entries) { (moduleId, componentSpec) ->
        addObject {
            encodeCoordinates(moduleId)
            encodeComponentSpec(componentSpec)
        }
    }
    addArrayIfNotEmpty("componentMappings", data.componentMappings.entries) { (componentId, componentSpec) ->
        addObject {
            encodeVersionedCoordinates(componentId)
            encodeComponentSpec(componentSpec)
        }
    }
}

private fun JsonObjectStringBuilder.encodeComponentSpec(component: OciImageMappingData.ComponentSpec) {
    encodeVariantSpec(component.mainVariant)
    addArrayIfNotEmpty("featureVariants", component.featureVariants.entries) { (name, variant) ->
        addObject {
            addString("name", name)
            encodeVariantSpec(variant)
        }
    }
}

private fun JsonObjectStringBuilder.encodeVariantSpec(variant: OciImageMappingData.VariantSpec) {
    addArrayIfNotEmpty("capabilities", variant.capabilities) { addObject { encodeCapabilitySpec(it) } }
    addNameSpecIfNotNull("imageName", variant.imageName)
    addNameSpecIfNotNull("imageTag", variant.imageTag)
}

private fun JsonObjectStringBuilder.encodeCapabilitySpec(capability: Triple<NameSpec, NameSpec, NameSpec>) {
    addNameSpec("group", capability.first)
    addNameSpec("name", capability.second)
    addNameSpec("version", capability.third)
}

fun String.decodeAsJsonToOciImageNameMappingData() = jsonObject(this).decodeOciImageNameMappingData()

fun JsonObject.decodeOciImageNameMappingData() = OciImageMappingData(
    getOrNull("groupMappings") {
        asArray().toMap(TreeMap()) { asObject().run { Pair(getString("group"), decodeComponentSpec()) } }
    } ?: TreeMap(),
    getOrNull("moduleMappings") {
        asArray().toMap(TreeMap()) { asObject().run { Pair(decodeCoordinates(), decodeComponentSpec()) } }
    } ?: TreeMap(),
    getOrNull("componentMappings") {
        asArray().toMap(TreeMap()) { asObject().run { Pair(decodeVersionedCoordinates(), decodeComponentSpec()) } }
    } ?: TreeMap(),
)

private fun JsonObject.decodeComponentSpec() = OciImageMappingData.ComponentSpec(
    decodeVariantSpec(),
    getOrNull("featureVariants") {
        asArray().toMap(TreeMap()) { asObject().run { Pair(getString("name"), decodeVariantSpec()) } }
    } ?: TreeMap(),
)

private fun JsonObject.decodeVariantSpec() = OciImageMappingData.VariantSpec(
    getOrNull("capabilities") { asArray().toList { asObject().decodeCapabilitySpec() } } ?: listOf(),
    getNameSpecOrNull("imageName"),
    getNameSpecOrNull("imageTag"),
)

private fun JsonObject.decodeCapabilitySpec() = Triple(
    getNameSpec("group"),
    getNameSpec("name"),
    getNameSpec("version"),
)
