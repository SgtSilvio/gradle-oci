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
internal class OciRegistryToken(val jws: String) {
    val payload = jsonObject(jws.decodeToJWS().payload).decodeOciRegistryTokenPayload()
}

internal data class OciRegistryTokenPayload(
    val issuer: String?,
    val subject: String?,
    val audience: List<String>,
    val expirationTime: Instant?,
    val notBefore: Instant?,
    val issuedAt: Instant?,
    val jwtId: String?,
    val scopes: Set<OciRegistryResourceScope>,
)

internal fun JsonObject.decodeOciRegistryTokenPayload() = OciRegistryTokenPayload(
    getStringOrNull("iss"),
    getStringOrNull("sub"),
    getOrNull("aud") { if (isString()) listOf(asString()) else asArray().toStringList() } ?: listOf(),
    getInstantOfEpochSecondOrNull("exp"),
    getInstantOfEpochSecondOrNull("nbf"),
    getInstantOfEpochSecondOrNull("iat"),
    getStringOrNull("jti"),
    getOrNull("access") { asArray().toSet(HashSet()) { asObject().decodeResourceScope() } } ?: setOf(),
)

private fun JsonObject.getInstantOfEpochSecondOrNull(key: String) = getOrNull(key) { Instant.ofEpochSecond(asLong()) }
