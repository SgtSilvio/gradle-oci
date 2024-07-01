package io.github.sgtsilvio.gradle.oci.platform

import io.github.sgtsilvio.gradle.oci.internal.compareTo
import java.io.Serializable
import java.util.*

/**
 * @author Silvio Giebl
 */
sealed interface Platform : Comparable<Platform>, Serializable {
    val os: String
    val architecture: String
    val variant: String
    val osVersion: String
    val osFeatures: SortedSet<String>
}

internal fun Platform(
    os: String,
    architecture: String,
    variant: String,
    osVersion: String,
    osFeatures: SortedSet<String>,
): Platform = PlatformImpl(os, architecture, variant.ifEmpty { defaultVariant(architecture) }, osVersion, osFeatures)

private data class PlatformImpl(
    override val os: String,
    override val architecture: String,
    override val variant: String,
    override val osVersion: String,
    override val osFeatures: SortedSet<String>,
) : Platform {

    override fun toString(): String {
        val s = "$os,$architecture"
        return when {
            osFeatures.isNotEmpty() -> "$s,$variant,$osVersion," + osFeatures.joinToString(",")
            osVersion.isNotEmpty() -> "$s,$variant,$osVersion"
            variant.isNotEmpty() -> "$s,$variant"
            else -> s
        }
    }

    override fun compareTo(other: Platform): Int {
        os.compareTo(other.os).also { if (it != 0) return it }
        architecture.compareTo(other.architecture).also { if (it != 0) return it }
        variant.compareTo(other.variant).also { if (it != 0) return it }
        osVersion.compareTo(other.osVersion).also { if (it != 0) return it }
        return osFeatures.compareTo(other.osFeatures)
    }
}

private fun defaultVariant(architecture: String) = when (architecture) {
    "arm64" -> "v8"
    "arm" -> "v7"
    else -> ""
}

internal fun String.toPlatform(): Platform {
    val parts = split(',')
    if (parts.size < 2) {
        throw IllegalArgumentException("'$this' is not a platform string")
    }
    return Platform(
        parts[0],
        parts[1],
        if (parts.size > 2) parts[2] else "",
        if (parts.size > 3) parts[3] else "",
        if (parts.size > 4) parts.subList(4, parts.size).toSortedSet() else TreeSet(),
    )
}
