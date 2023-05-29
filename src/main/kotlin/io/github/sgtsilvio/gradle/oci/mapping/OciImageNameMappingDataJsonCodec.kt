package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.internal.json.*
import java.util.*

fun OciImageNameMappingData.encodeToJsonString() = jsonObject { encodeOciImageNameMappingData(this@encodeToJsonString) }

private fun JsonObjectStringBuilder.encodeOciImageNameMappingData(data: OciImageNameMappingData) {
    addArrayIfNotEmpty("groupMappings", data.groupMappings.entries) { (group, componentSpec) ->
        addObject {
            addString("group", group)
            encodeComponentSpec(componentSpec)
        }
    }
    addArrayIfNotEmpty("moduleMappings", data.moduleMappings.entries) { (module, componentSpec) ->
        addObject {
            addString("group", module.first)
            addString("name", module.second)
            encodeComponentSpec(componentSpec)
        }
    }
    addArrayIfNotEmpty("componentMappings", data.componentMappings.entries) { (component, componentSpec) ->
        addObject {
            addString("group", component.first)
            addString("name", component.second)
            addString("version", component.third)
            encodeComponentSpec(componentSpec)
        }
    }
}

private fun JsonObjectStringBuilder.encodeComponentSpec(component: OciImageNameMappingData.ComponentSpec) {
    encodeVariantSpec(component.mainVariant)
    addArrayIfNotEmpty("featureVariants", component.featureVariants.entries) { (name, variant) ->
        addObject {
            addString("name", name)
            encodeVariantSpec(variant)
        }
    }
}

private fun JsonObjectStringBuilder.encodeVariantSpec(variant: OciImageNameMappingData.VariantSpec) {
    addArrayIfNotEmpty("capabilities", variant.capabilities) { addObject { encodeCapabilitySpec(it) } }
    addNameSpecIfNotNull("imageName", variant.imageName)
    addNameSpecIfNotNull("tagName", variant.tagName)
}

private fun JsonObjectStringBuilder.encodeCapabilitySpec(capability: Triple<NameSpec, NameSpec, NameSpec>) {
    addNameSpec("group", capability.first)
    addNameSpec("name", capability.second)
    addNameSpec("version", capability.third)
}

private fun JsonObjectStringBuilder.addNameSpecIfNotNull(key: String, nameSpec: NameSpec?) {
    if (nameSpec != null) {
        addNameSpec(key, nameSpec)
    }
}

fun String.decodeAsJsonToOciImageNameMappingData() = jsonObject(this).decodeOciImageNameMappingData()

private fun JsonObject.decodeOciImageNameMappingData() = OciImageNameMappingData(
    getOrNull("groupMappings") { asArray().toMap(TreeMap()) { asObject().decodeGroupMapping() } } ?: TreeMap(),
    getOrNull("moduleMappings") { asArray().toMap(TreeMap()) { asObject().decodeModuleMapping() } } ?: TreeMap(),
    getOrNull("componentMappings") { asArray().toMap(TreeMap()) { asObject().decodeComponentMapping() } } ?: TreeMap(),
)

private fun JsonObject.decodeGroupMapping() = Pair(
    getString("group"),
    decodeComponentSpec(),
)

private fun JsonObject.decodeModuleMapping() = Pair(
    Pair(getString("group"), getString("name")),
    decodeComponentSpec(),
)

private fun JsonObject.decodeComponentMapping() = Pair(
    Triple(getString("group"), getString("name"), getString("version")),
    decodeComponentSpec(),
)

private fun JsonObject.decodeComponentSpec() = OciImageNameMappingData.ComponentSpec(
    decodeVariantSpec(),
    getOrNull("featureVariants") {
        asArray().toMap(TreeMap()) {
            asObject().run { Pair(getString("name"), decodeVariantSpec()) }
        }
    } ?: TreeMap(),
)

private fun JsonObject.decodeVariantSpec() = OciImageNameMappingData.VariantSpec(
    getOrNull("capabilities") { asArray().toList { asObject().decodeCapabilitySpec() } } ?: listOf(),
    getNameSpecOrNull("imageName"),
    getNameSpecOrNull("tagName"),
)

private fun JsonObject.decodeCapabilitySpec() = Triple(
    getNameSpec("group"),
    getNameSpec("name"),
    getNameSpec("version"),
)

private fun JsonObject.getNameSpec(key: String) = get(key) { decodeNameSpec() }

private fun JsonObject.getNameSpecOrNull(key: String) = getOrNull(key) { decodeNameSpec() }
