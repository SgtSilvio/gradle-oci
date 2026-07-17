package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.JsonObject
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.getStringSetOrEmpty

/**
 * @author Silvio Giebl
 */
internal data class OciRegistryResourceScope(val type: String, val name: String, val actions: Set<String>)

internal fun JsonObject.decodeResourceScope() = OciRegistryResourceScope(
    getString("type"),
    getString("name"),
    getStringSetOrEmpty("actions"),
)
