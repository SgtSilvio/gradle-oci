package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.getStringSetOrEmpty

/**
 * @author Silvio Giebl
 */
internal data class OciRegistryResourceScope(val type: String, val name: String, val actions: Set<String>) {

    fun encodeToString() = "$type:$name:" + actions.joinToString(",")
}

internal fun JsonObject.decodeResourceScope() = OciRegistryResourceScope(
    getString("type"),
    getString("name"),
    getStringSetOrEmpty("actions"),
)

internal fun String.decodeToResourceScopeOrNull(): OciRegistryResourceScope? {
    val parts = split(':')
    if (parts.size != 3) return null
    return OciRegistryResourceScope(parts[0], parts[1], parts[2].split(',').toSet())
}
