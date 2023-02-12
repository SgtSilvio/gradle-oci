package io.github.sgtsilvio.gradle.oci.component

import java.io.Serializable

/**
 * @author Silvio Giebl
 */
data class Capability(val group: String, val name: String) : Comparable<Capability>, Serializable {
    override fun compareTo(other: Capability): Int {
        group.compareTo(other.group).also { if (it != 0) return it }
        return name.compareTo(other.name)
    }
}

data class VersionedCapability(
    val capability: Capability,
    val version: String,
) : Comparable<VersionedCapability>, Serializable {
    override fun compareTo(other: VersionedCapability): Int {
        capability.compareTo(other.capability).also { if (it != 0) return it }
        return version.compareTo(other.version)
    }
}