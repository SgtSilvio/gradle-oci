package io.github.sgtsilvio.gradle.oci.mapping

/**
 * @author Silvio Giebl
 */
data class OciImageName(val namespace: String, val name: String, val tag: String)