package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.metadata.LAYER_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.getOciDigest
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReference
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import java.util.*

internal fun String.decodeAsJsonToOciComponent() = jsonObject(this).decodeOciComponent()

private fun JsonObject.decodeOciComponent() = OciComponent(
    get("imageReference") { asString().toOciImageReference() },
    getOrNull("capabilities") { asArray().toSet(TreeSet()) { asObject().decodeVersionedCoordinates() } } ?: TreeSet(),
    if (hasKey("bundle")) {
        if (hasKey("platformBundles")) throw JsonException.create("bundle|platformBundles", "must not both be present")
        get("bundle") { asObject().decodeBundle() }
    } else {
        get("platformBundles") { asArray().decodePlatformBundles() }
    },
    getStringMapOrNull("indexAnnotations") ?: TreeMap(),
)

private fun JsonArray.decodePlatformBundles() = OciComponent.PlatformBundles(toMap(TreeMap()) {
    asObject().run {
        Pair(
            get("platform") { asObject().decodePlatform() },
            get("bundle") { asObject().decodeBundle() },
        )
    }
})

private fun JsonObject.decodePlatform() = PlatformImpl(
    getString("os"),
    getString("architecture"),
    getStringOrNull("variant") ?: "",
    getStringOrNull("osVersion") ?: "",
    getStringSetOrNull("osFeatures") ?: TreeSet(),
)

private fun JsonObject.decodeBundle() = OciComponent.Bundle(
    getOrNull("parentCapabilities") { asArray().toList { asObject().decodeCoordinates() } } ?: listOf(),
    getInstantOrNull("creationTime"),
    getStringOrNull("author"),
    getStringOrNull("user"),
    getStringSetOrNull("ports") ?: TreeSet(),
    getStringMapOrNull("environment") ?: TreeMap(),
    getOrNull("command") { asObject().decodeCommand() },
    getStringSetOrNull("volumes") ?: TreeSet(),
    getStringOrNull("workingDirectory"),
    getStringOrNull("stopSignal"),
    getStringMapOrNull("configAnnotations") ?: TreeMap(),
    getStringMapOrNull("configDescriptorAnnotations") ?: TreeMap(),
    getStringMapOrNull("manifestAnnotations") ?: TreeMap(),
    getStringMapOrNull("manifestDescriptorAnnotations") ?: TreeMap(),
    getOrNull("layers") { asArray().toList { asObject().decodeLayer() } } ?: listOf(),
)

private fun JsonObject.decodeCommand() = OciComponent.Bundle.Command(
    getStringListOrNull("entryPoint"),
    getStringList("arguments"),
)

private fun JsonObject.decodeLayer() = OciComponent.Bundle.Layer(
    getOrNull("descriptor") { asObject().decodeLayerDescriptor() },
    getInstantOrNull("creationTime"),
    getStringOrNull("author"),
    getStringOrNull("createdBy"),
    getStringOrNull("comment"),
)

private fun JsonObject.decodeLayerDescriptor() = OciComponent.Bundle.Layer.Descriptor(
    getStringOrNull("metadata") ?: LAYER_MEDIA_TYPE,
    getOciDigest("digest"),
    getLong("size"),
    getOciDigest("diffId"),
    getStringMapOrNull("annotations") ?: TreeMap(),
)
