package io.github.sgtsilvio.gradle.oci.metadata

import java.util.*

/**
 * @author Silvio Giebl
 */
interface OciDescriptor {
    val mediaType: String
    val digest: OciDigest
    val size: Long
    val annotations: SortedMap<String, String>
}

data class OciDescriptorImpl(
    override val mediaType: String,
    override val digest: OciDigest,
    override val size: Long,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor

class OciDataDescriptor(
    override val mediaType: String,
    val data: ByteArray,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor {
    override val digest = data.calculateOciDigest(OciDigestAlgorithm.SHA_256)
    override val size get() = data.size.toLong()
}
