package io.github.sgtsilvio.gradle.oci.dsl

/**
 * @author Silvio Giebl
 */
data class Capability(val group: String, val name: String, val version: String) {
    init {
        require(group.isNotEmpty()) { "capability group must not be empty" }
        require(name.isNotEmpty()) { "capability name must not be empty" }
        require(version.isNotEmpty()) { "capability version must not be empty" }
    }
}

internal fun Capability.toMapNotation() = mapOf("group" to group, "name" to name, "version" to version)

interface CapabilityFactories {
    fun capability(group: String, name: String, version: String) = Capability(group, name, version)
}
