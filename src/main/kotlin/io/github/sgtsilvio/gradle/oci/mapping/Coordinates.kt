package io.github.sgtsilvio.gradle.oci.mapping

import java.io.Serializable

/**
 * @author Silvio Giebl
 */
internal data class Coordinates(val group: String, val name: String) : Comparable<Coordinates>, Serializable {
    override fun compareTo(other: Coordinates): Int {
        group.compareTo(other.group).also { if (it != 0) return it }
        return name.compareTo(other.name)
    }

    override fun toString() = "$group:$name"
}

internal data class VersionedCoordinates(
    val coordinates: Coordinates,
    val version: String,
) : Comparable<VersionedCoordinates>, Serializable {
    val group get() = coordinates.group
    val name get() = coordinates.name

    constructor(group: String, name: String, version: String) : this(Coordinates(group, name), version)

    override fun compareTo(other: VersionedCoordinates): Int {
        coordinates.compareTo(other.coordinates).also { if (it != 0) return it }
        return version.compareTo(other.version)
    }

    override fun toString() = "$group:$name:$version"
}
