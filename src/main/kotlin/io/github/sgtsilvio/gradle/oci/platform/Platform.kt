package io.github.sgtsilvio.gradle.oci.platform

import io.github.sgtsilvio.gradle.oci.internal.string.escapeReplace
import io.github.sgtsilvio.gradle.oci.internal.string.unescapeReplace
import java.io.Serializable
import java.util.*

/**
 * @author Silvio Giebl
 */
sealed interface Platform : Serializable {
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

    override fun toString() = buildString {
        append(os.escapeReplace(',', '$'))
        append(',')
        append(architecture.escapeReplace(',', '$'))
        if (variant.isNotEmpty() || osVersion.isNotEmpty() || osFeatures.isNotEmpty()) {
            append(',')
            append(variant.escapeReplace(',', '$'))
            if (osVersion.isNotEmpty() || osFeatures.isNotEmpty()) {
                append(',')
                append(osVersion.escapeReplace(',', '$'))
                for (osFeature in osFeatures) {
                    append(',')
                    append(osFeature.escapeReplace(',', '$'))
                }
            }
        }
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
        parts[0].unescapeReplace(',', '$'),
        parts[1].unescapeReplace(',', '$'),
        if (parts.size > 2) parts[2].unescapeReplace(',', '$') else "",
        if (parts.size > 3) parts[3].unescapeReplace(',', '$') else "",
        if (parts.size > 4) {
            parts.subList(4, parts.size).mapTo(TreeSet()) { it.unescapeReplace(',', '$') }
        } else TreeSet(),
    )
}
