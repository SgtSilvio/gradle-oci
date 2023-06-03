package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
data class OciImageName(val imageName: String, val tagName: String) {
    override fun toString() = "$imageName:$tagName"
}

fun String.toOciImageName() = when (val separatorIndex = lastIndexOf(':')) {
    -1 -> OciImageName(this, "latest")
    else -> OciImageName(substring(0, separatorIndex), substring(separatorIndex + 1))
}

/*
TODO proper naming
imageId = name ':' reference
reference = tag | digest
digest = algorithm ':' encodedHash
 */
