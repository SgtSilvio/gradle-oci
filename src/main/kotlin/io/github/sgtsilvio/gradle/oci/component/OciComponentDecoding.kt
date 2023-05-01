package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.metadata.getOciDigest
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import java.time.Instant
import java.util.*

fun decodeComponent(string: String) = jsonObject(string).decodeComponent()

private fun JsonObject.decodeComponent() = OciComponent(
    get("capabilities") { asArray().toSet(TreeSet()) { asObject().decodeVersionedCapability() } },
    if (hasKey("bundle")) {
        if (hasKey("platformBundles")) throw JsonException.create("bundle|platformBundles", "must not both be present")
        get("bundle") { asObject().decodeBundle() }
    } else {
        get("platformBundles") { asArray().decodePlatformBundles() }
    },
    getStringMapOrNull("indexAnnotations") ?: TreeMap(),
)

private fun JsonObject.decodeCapability() = Capability(getString("group"), getString("name"))

private fun JsonObject.decodeVersionedCapability() = VersionedCapability(decodeCapability(), getString("version"))

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
    getOrNull("parentCapabilities") { asArray().toList { asObject().decodeCapability() } } ?: listOf(),
    get("layers") { asArray().toList { asObject().decodeLayer() } },
)

private fun JsonObject.decodeCommand() = OciComponent.Bundle.Command(
    getStringListOrNull("entryPoint"),
    getStringList("arguments"),
)

private fun JsonObject.decodeLayer() = OciComponent.Bundle.Layer(
    if (hasKey("digest") || hasKey("diffId") || hasKey("size") || hasKey("annotations")) {
        OciComponent.Bundle.Layer.Descriptor(
            getOciDigest("digest"),
            getOciDigest("diffId"),
            getLong("size"),
            getStringMapOrNull("annotations") ?: TreeMap(),
        )
    } else null,
    getInstantOrNull("creationTime"),
    getStringOrNull("author"),
    getStringOrNull("createdBy"),
    getStringOrNull("comment"),
)

private fun JsonObject.getInstantOrNull(key: String) = getOrNull(key) { Instant.parse(asString()) }