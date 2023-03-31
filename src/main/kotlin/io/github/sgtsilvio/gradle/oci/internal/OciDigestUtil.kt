package io.github.sgtsilvio.gradle.oci.internal

import java.security.MessageDigest

private fun ByteArray.toHex() = joinTo(StringBuilder(2 * size), "") { "%02x".format(it) }.toString()

internal fun newSha256MessageDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

internal fun formatSha256Digest(digest: ByteArray) = "sha256:" + digest.toHex()

internal fun calculateSha256Digest(data: ByteArray) = formatSha256Digest(newSha256MessageDigest().digest(data))