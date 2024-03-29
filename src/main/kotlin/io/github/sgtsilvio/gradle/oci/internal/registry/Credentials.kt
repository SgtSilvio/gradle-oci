package io.github.sgtsilvio.gradle.oci.internal.registry

import java.security.MessageDigest

internal class Credentials(val username: String, val password: String)

internal class HashedCredentials(val username: String, val hashedPassword: ByteArray) {
    override fun equals(other: Any?) = when {
        this === other -> true
        other !is HashedCredentials -> false
        username != other.username -> false
        !hashedPassword.contentEquals(other.hashedPassword) -> false
        else -> true
    }

    override fun hashCode(): Int {
        var result = username.hashCode()
        result = 31 * result + hashedPassword.contentHashCode()
        return result
    }
}

internal fun Credentials.hashed() =
    HashedCredentials(username, MessageDigest.getInstance("SHA-256").digest(password.toByteArray()))
