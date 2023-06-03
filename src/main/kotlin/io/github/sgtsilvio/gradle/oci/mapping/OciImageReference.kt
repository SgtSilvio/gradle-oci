package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
data class OciImageReference(val name: String, val tag: String) {
    override fun toString() = "$name:$tag"
}

fun String.toOciImageReference() = when (val separatorIndex = lastIndexOf(':')) {
    -1 -> OciImageReference(this, "latest")
    else -> OciImageReference(substring(0, separatorIndex), substring(separatorIndex + 1))
}
