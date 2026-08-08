package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.JsonException
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.internal.jwt.decodeToJWS
import java.time.DateTimeException
import java.time.Instant

/**
 * @author Silvio Giebl
 */
internal class OciRegistryToken(
    val token: String,
    val expirationTime: Instant,
    val scopes: Set<OciRegistryResourceScope>?,
)

internal fun OciRegistryToken(token: String, expirationTime: Instant): OciRegistryToken {
    return try {
        val jws = token.decodeToJWS()
        val jwtClaimsJsonObject = jsonObject(jws.payload.decodeToString())
        val jwtExpirationTime = try {
            jwtClaimsJsonObject.getOrNull("exp") { Instant.ofEpochSecond(asLong()) }
        } catch (e: JsonException) {
            null
        } catch (e: DateTimeException) {
            null
        }
        val scopes = try {
            jwtClaimsJsonObject.getOrNull("access") {
                asArray().toSet(HashSet()) { asObject().decodeResourceScope() }
            }
        } catch (e: JsonException) {
            null
        }
        OciRegistryToken(token, jwtExpirationTime ?: expirationTime, scopes)
    } catch (e: IllegalArgumentException) {
        OciRegistryToken(token, expirationTime, null)
    } catch (e: JsonException) {
        OciRegistryToken(token, expirationTime, null)
    }
}

internal fun Set<OciRegistryResourceScope>.includesAll(required: Set<OciRegistryResourceScope>): Boolean =
    required.all { (type, name, actions) ->
        val scope = find { (it.type == type) && (it.name == name) }
        (scope != null) && scope.actions.containsAll(actions)
    }
