package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.getStringSetOrNull

/**
 * @author Silvio Giebl
 */
data class OciRegistryResourceScope(val type: String, val name: String, val actions: Set<String>) {

    fun encodeToString() = "$type:$name:" + actions.joinToString(",")
}

fun JsonObject.decodeResourceScope() = OciRegistryResourceScope(
    getString("type"),
    getString("name"),
    getStringSetOrNull("actions") ?: setOf(),
)

fun String.decodeToResourceScope(): OciRegistryResourceScope {
    val parts = split(':')
    require(parts.size == 3) { "'$this' is not a valid resource scope, required: 3 parts, actual: ${parts.size} parts" }
    return OciRegistryResourceScope(parts[0], parts[1], parts[2].split(',').toSet())
}
