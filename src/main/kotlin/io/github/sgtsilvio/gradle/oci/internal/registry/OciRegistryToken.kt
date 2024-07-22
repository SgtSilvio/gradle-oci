package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.getStringOrNull
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.toStringList
import io.github.sgtsilvio.gradle.oci.internal.jwt.decodeToJWS
import java.time.Instant

/**
 * @author Silvio Giebl
 */
internal class OciRegistryToken(val token: String, val claims: OciRegistryTokenClaims?)

internal fun OciRegistryToken(token: String): OciRegistryToken {
    val jws = try {
        token.decodeToJWS()
    } catch (e: IllegalArgumentException) {
        return OciRegistryToken(token, null)
    }
    val claims = jsonObject(jws.payload).decodeOciRegistryTokenClaims()
    return OciRegistryToken(token, claims)
}

internal data class OciRegistryTokenClaims(
    val issuer: String?,
    val subject: String?,
    val audience: List<String>,
    val expirationTime: Instant?,
    val notBefore: Instant?,
    val issuedAt: Instant?,
    val jwtId: String?,
    val scopes: Set<OciRegistryResourceScope>,
)

private fun JsonObject.decodeOciRegistryTokenClaims() = OciRegistryTokenClaims(
    getStringOrNull("iss"),
    getStringOrNull("sub"),
    getOrNull("aud") { if (isString()) listOf(asString()) else asArray().toStringList() } ?: emptyList(),
    getInstantOfEpochSecondOrNull("exp"),
    getInstantOfEpochSecondOrNull("nbf"),
    getInstantOfEpochSecondOrNull("iat"),
    getStringOrNull("jti"),
    getOrNull("access") { asArray().toSet(HashSet()) { asObject().decodeResourceScope() } } ?: emptySet(),
)

private fun JsonObject.getInstantOfEpochSecondOrNull(key: String) = getOrNull(key) { Instant.ofEpochSecond(asLong()) }
