package io.github.sgtsilvio.gradle.oci.component

import java.io.Serializable

/**
 * @author Silvio Giebl
 */
data class Coordinates(val group: String, val name: String) : Comparable<Coordinates>, Serializable {
    override fun compareTo(other: Coordinates): Int {
        group.compareTo(other.group).also { if (it != 0) return it }
        return name.compareTo(other.name)
    }
}

data class VersionedCoordinates(
    val coordinates: Coordinates,
    val version: String,
) : Comparable<VersionedCoordinates>, Serializable {
    override fun compareTo(other: VersionedCoordinates): Int {
        coordinates.compareTo(other.coordinates).also { if (it != 0) return it }
        return version.compareTo(other.version)
    }
}
