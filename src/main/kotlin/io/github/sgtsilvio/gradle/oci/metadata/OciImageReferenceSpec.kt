package io.github.sgtsilvio.gradle.oci.metadata

import java.io.Serializable

/**
 * @author Silvio Giebl
 */
data class OciImageReferenceSpec(val name: String?, val tag: String?) : Serializable {
    override fun toString() = (name ?: "") + ":" + (tag ?: "")
}

internal val DEFAULT_OCI_IMAGE_REFERENCE_SPEC = OciImageReferenceSpec(null, null)

internal fun OciImageReferenceSpec.materialize(default: OciImageReference) =
    OciImageReference(name ?: default.name, tag ?: default.tag)

internal fun String.toOciImageReferenceSpec(): OciImageReferenceSpec {
    val parts = split(':')
    require(parts.size == 2) { "'$this' must contain exactly one ':' character" }
    return OciImageReferenceSpec(parts[0].takeIf { it.isNotEmpty() }, parts[1].takeIf { it.isNotEmpty() })
}
