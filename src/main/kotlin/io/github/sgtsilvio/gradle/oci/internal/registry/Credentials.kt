package io.github.sgtsilvio.gradle.oci.internal.registry

import java.security.MessageDigest

internal class Credentials(val username: String, val password: String)

internal class HashedCredentials(val username: String, val hashedPassword: ByteArray) {

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is HashedCredentials -> false
        else -> (username == other.username) && hashedPassword.contentEquals(other.hashedPassword)
    }

    override fun hashCode() = username.hashCode() * 31 + hashedPassword.contentHashCode()
}

internal fun Credentials.hashed() =
    HashedCredentials(username, MessageDigest.getInstance("SHA-256").digest(password.toByteArray()))
