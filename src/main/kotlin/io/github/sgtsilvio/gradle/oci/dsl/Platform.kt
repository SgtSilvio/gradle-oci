package io.github.sgtsilvio.gradle.oci.dsl

/**
 * @author Silvio Giebl
 */
interface Platform {
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
    override val osFeatures: Set<String>,
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
}