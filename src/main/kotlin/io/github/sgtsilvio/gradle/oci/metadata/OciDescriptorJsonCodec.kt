package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.JsonObjectStringBuilder
import io.github.sgtsilvio.gradle.oci.internal.json.addArrayIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.addObjectIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.addStringIfNotEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.getLong
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.getStringMapOrEmpty
import io.github.sgtsilvio.gradle.oci.internal.json.getStringOrNull
import io.github.sgtsilvio.gradle.oci.internal.json.getStringSetOrEmpty
import io.github.sgtsilvio.gradle.oci.platform.Platform

internal fun JsonObjectStringBuilder.encodeOciDescriptor(descriptor: OciDescriptor) {
    // sorted for canonical json: annotations, digest, mediaType, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest.toString())
    addString("mediaType", descriptor.mediaType)
    addNumber("size", descriptor.size)
}

internal fun JsonObjectStringBuilder.encodeOciManifestDescriptor(descriptor: OciDescriptor, platform: Platform) {
    // sorted for canonical json: annotations, digest, mediaType, platform, size
    addObjectIfNotEmpty("annotations", descriptor.annotations)
    addString("digest", descriptor.digest.toString())
    addString("mediaType", descriptor.mediaType)
    addObject("platform") { encodePlatform(platform) }
    addNumber("size", descriptor.size)
}

private fun JsonObjectStringBuilder.encodePlatform(platform: Platform) {
    // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
    addString("architecture", platform.architecture)
    addString("os", platform.os)
    addArrayIfNotEmpty("os.features", platform.osFeatures)
    addStringIfNotEmpty("os.version", platform.osVersion)
    addStringIfNotEmpty("variant", platform.variant)
}


internal fun JsonObject.decodeOciDescriptor() = OciDescriptorImpl(
    getString("mediaType"),
    getOciDigest("digest"),
    getLong("size"),
    getStringMapOrEmpty("annotations"),
)
// TODO support data

internal fun JsonObject.decodeOciManifestDescriptor() = Pair(
    getOrNull("platform") { asObject().decodePlatform() },
    decodeOciDescriptor(),
)

private fun JsonObject.decodePlatform() = Platform(
    getString("os"),
    getString("architecture"),
    getStringOrNull("variant") ?: "",
    getStringOrNull("os.version") ?: "",
    getStringSetOrEmpty("os.features"),
)
