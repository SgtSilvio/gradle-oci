package io.github.sgtsilvio.gradle.oci.internal

import java.security.MessageDigest

fun ByteArray.toHex() = joinTo(StringBuilder(2 * size), "") { "%02x".format(it) }.toString()

fun ByteArray.sha256Digest() = "sha256:" + MessageDigest.getInstance("SHA-256").digest(this).toHex()