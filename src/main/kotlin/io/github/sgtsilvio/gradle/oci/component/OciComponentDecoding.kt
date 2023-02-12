package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.JsonArray
import io.github.sgtsilvio.gradle.oci.internal.json.JsonException
import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import java.time.Instant
import java.util.*

fun decodeComponent(string: String) = jsonObject(string).decodeComponent()

private fun JsonObject.decodeComponent() = OciComponent(
    key("capabilities") { arrayValue().toSet(TreeSet()) { objectValue().decodeVersionedCapability() } },
    if (hasKey("bundle")) {
        if (hasKey("platformBundles")) throw JsonException("bundle|platformBundles", "must not both be present")
        key("bundle") { objectValue().decodeBundle() }
    } else {
        key("platformBundles") { arrayValue().decodePlatformBundles() }
    },
    optionalKey("indexAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
)

private fun JsonObject.decodeCapability() = Capability(
    key("group") { stringValue() },
    key("name") { stringValue() },
)

private fun JsonObject.decodeVersionedCapability() = VersionedCapability(
    decodeCapability(),
    key("version") { stringValue() },
)

private fun JsonArray.decodePlatformBundles() = OciComponent.PlatformBundles(toMap(TreeMap()) {
    objectValue().run {
        Pair(
            key("platform") { objectValue().decodePlatform() },
            key("bundle") { objectValue().decodeBundle() },
        )
    }
})

private fun JsonObject.decodePlatform() = PlatformImpl(
    key("os") { stringValue() },
    key("architecture") { stringValue() },
    optionalKey("variant") { stringValue() } ?: "",
    optionalKey("osVersion") { stringValue() } ?: "",
    optionalKey("osFeatures") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
)

private fun JsonObject.decodeBundle() = OciComponent.Bundle(
    optionalKey("creationTime") { Instant.parse(stringValue()) },
    optionalKey("author") { stringValue() },
    optionalKey("user") { stringValue() },
    optionalKey("ports") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
    optionalKey("environment") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("command") { objectValue().decodeCommand() },
    optionalKey("volumes") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
    optionalKey("workingDirectory") { stringValue() },
    optionalKey("stopSignal") { stringValue() },
    optionalKey("configAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("configDescriptorAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("manifestAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("manifestDescriptorAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("parentCapabilities") { arrayValue().toList { objectValue().decodeCapability() } } ?: listOf(),
    key("layers") { arrayValue().toList { objectValue().decodeLayer() } },
)

private fun JsonObject.decodeCommand() = OciComponent.Bundle.Command(
    optionalKey("entryPoint") { arrayValue().toList { stringValue() } },
    key("arguments") { arrayValue().toList { stringValue() } },
)

private fun JsonObject.decodeLayer() = OciComponent.Bundle.Layer(
    if (hasKey("digest") || hasKey("diffId") || hasKey("size") || hasKey("annotations")) {
        OciComponent.Bundle.Layer.Descriptor(
            key("digest") { stringValue() },
            key("diffId") { stringValue() },
            key("size") { longValue() },
            optionalKey("annotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
        )
    } else null,
    optionalKey("creationTime") { Instant.parse(stringValue()) },
    optionalKey("author") { stringValue() },
    optionalKey("createdBy") { stringValue() },
    optionalKey("comment") { stringValue() },
)