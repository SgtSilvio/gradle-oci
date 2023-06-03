package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
data class OciImageId(val name: String, val tag: String) {
    override fun toString() = "$name:$tag"
}

fun String.toOciImageId() = when (val separatorIndex = lastIndexOf(':')) {
    -1 -> OciImageId(this, "latest")
    else -> OciImageId(substring(0, separatorIndex), substring(separatorIndex + 1))
}
