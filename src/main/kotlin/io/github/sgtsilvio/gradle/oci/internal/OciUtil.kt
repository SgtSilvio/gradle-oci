package io.github.sgtsilvio.gradle.oci.internal

import java.security.MessageDigest
import java.time.Instant

const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"
const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
const val CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
const val LAYER_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip"

val DEFAULT_MODIFICATION_TIME: Instant = Instant.ofEpochSecond(1)

fun ByteArray.toHex() = joinTo(StringBuilder(2 * size), "") { "%02x".format(it) }.toString()

fun newSha256MessageDigest() : MessageDigest = MessageDigest.getInstance("SHA-256")

fun formatSha256Digest(data: ByteArray) = "sha256:" + data.toHex()

fun calculateSha256Digest(data: ByteArray) = formatSha256Digest(newSha256MessageDigest().digest(data))