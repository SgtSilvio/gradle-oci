package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.old.OciDescriptor
import io.github.sgtsilvio.gradle.oci.old.OciManifestDescriptor
import java.security.MessageDigest
import java.time.Instant

const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"
const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
const val CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
const val LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"

val DEFAULT_MODIFICATION_TIME: Instant = Instant.ofEpochSecond(1)

fun ByteArray.toHex() = joinTo(StringBuilder(2 * size), "") { "%02x".format(it) }.toString()

fun newSha256MessageDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

fun formatSha256Digest(digest: ByteArray) = "sha256:" + digest.toHex()

fun calculateSha256Digest(data: ByteArray) = formatSha256Digest(newSha256MessageDigest().digest(data))

fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, ociDescriptor: OciDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addKeyAndObjectIfNotEmpty("annotations", ociDescriptor.annotations.orNull)
        descriptorObject.addKeyAndStringIfNotNull("data", ociDescriptor.data.orNull)
        descriptorObject.addKey("digest").addString(ociDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addString(mediaType)
        descriptorObject.addKey("size").addNumber(ociDescriptor.size.get())
        descriptorObject.addKeyAndArrayIfNotEmpty("urls", ociDescriptor.urls.orNull)
    }
}

fun JsonValueStringBuilder.addOciManifestDescriptor(ociManifestDescriptor: OciManifestDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addKeyAndObjectIfNotEmpty("annotations", ociManifestDescriptor.annotations.orNull)
        descriptorObject.addKeyAndStringIfNotNull("data", ociManifestDescriptor.data.orNull)
        descriptorObject.addKey("digest").addString(ociManifestDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addString(MANIFEST_MEDIA_TYPE)
        descriptorObject.addKey("platform").addObject { platformObject ->
            // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
            platformObject.addKey("architecture").addString(ociManifestDescriptor.platform.architecture.get())
            platformObject.addKey("os").addString(ociManifestDescriptor.platform.os.get())
            platformObject.addKeyAndArrayIfNotEmpty("os.features", ociManifestDescriptor.platform.osFeatures.orNull)
            platformObject.addKeyAndStringIfNotNull("os.version", ociManifestDescriptor.platform.osVersion.orNull)
            platformObject.addKeyAndStringIfNotNull("variant", ociManifestDescriptor.platform.variant.orNull)
        }
        descriptorObject.addKey("size").addNumber(ociManifestDescriptor.size.get())
        descriptorObject.addKeyAndArrayIfNotEmpty("urls", ociManifestDescriptor.urls.orNull)
    }
}