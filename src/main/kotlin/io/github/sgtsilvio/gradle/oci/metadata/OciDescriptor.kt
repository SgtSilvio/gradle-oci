package io.github.sgtsilvio.gradle.oci.metadata

import java.util.*

/**
 * @author Silvio Giebl
 */
interface OciDescriptor {
    val digest: String
    val size: Long
    val annotations: SortedMap<String, String>
}

data class OciDescriptorImpl(
    override val digest: String,
    override val size: Long,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor

class OciDataDescriptor(
    val data: ByteArray,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor {
    override val digest = data.calculateOciDigest(OciDigestAlgorithm.SHA_256).toString()
    override val size get() = data.size.toLong()
}