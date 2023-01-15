package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.internal.compareTo

/**
 * @author Silvio Giebl
 */
interface Platform : Comparable<Platform> {
    val os: String
    val architecture: String
    val variant: String
    val osVersion: String
    val osFeatures: Set<String>
}

data class PlatformImpl(
    override val os: String,
    override val architecture: String,
    override val variant: String,
    override val osVersion: String,
    override val osFeatures: Set<String>, // TODO sorted
) : Platform {

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
}