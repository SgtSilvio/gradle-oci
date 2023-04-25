package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.OciDigestAlgorithm
import io.github.sgtsilvio.gradle.oci.internal.calculateOciDigest
import java.util.*

/**
 * @author Silvio Giebl
 */
interface OciDescriptor {
    val digest: String
    val size: Long
    val annotations: SortedMap<String, String>
}

class OciDataDescriptor(
    val data: ByteArray,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor {
    override val digest = data.calculateOciDigest(OciDigestAlgorithm.SHA_256).toString()
    override val size get() = data.size.toLong()
}