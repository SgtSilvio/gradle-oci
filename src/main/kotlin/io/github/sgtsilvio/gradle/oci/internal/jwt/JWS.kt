package io.github.sgtsilvio.gradle.oci.internal.jwt

import java.util.Base64

/**
 * @author Silvio Giebl
 */
class JWS(val header: String, val payload: String, val signature: ByteArray) {

    fun encodeToString(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(header.toByteArray()) + '.' +
                Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + '.' +
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
}

fun String.decodeToJWS(): JWS {
    val parts = split('.')
    require(parts.size == 3) { "'$this' is not a valid JWS, required: 3 parts, actual: ${parts.size} parts" }
    return JWS(
        String(Base64.getUrlDecoder().decode(parts[0])),
        String(Base64.getUrlDecoder().decode(parts[1])),
        Base64.getUrlDecoder().decode(parts[2]),
    )
}
