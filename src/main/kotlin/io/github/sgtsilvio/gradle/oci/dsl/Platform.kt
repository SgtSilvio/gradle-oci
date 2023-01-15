package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.internal.compareTo
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
interface Platform : Comparable<Platform>, Serializable {
    val os: String
    val architecture: String
    val variant: String
    val osVersion: String
    val osFeatures: Set<String>
}

class PlatformImpl(
    override val os: String,
    override val architecture: String,
    override val variant: String,
    override val osVersion: String,
    osFeatures: Set<String>,
) : Platform {
    override val osFeatures = osFeatures.toSortedSet().toSet()

    override fun toString(): String {
        val s = "@$os,$architecture"
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
        osFeatures.compareTo(other.osFeatures).also { if (it != 0) return it }
        return 0
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is PlatformImpl -> false
        os != other.os -> false
        architecture != other.architecture -> false
        variant != other.variant -> false
        osVersion != other.osVersion -> false
        osFeatures != other.osFeatures -> false
        else -> true
    }

    override fun hashCode(): Int {
        var result = os.hashCode()
        result = 31 * result + architecture.hashCode()
        result = 31 * result + variant.hashCode()
        result = 31 * result + osVersion.hashCode()
        result = 31 * result + osFeatures.hashCode()
        return result
    }
}