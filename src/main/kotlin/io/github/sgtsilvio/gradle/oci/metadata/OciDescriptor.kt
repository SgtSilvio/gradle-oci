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

internal data class OciDescriptorImpl(
    override val mediaType: String,
    override val digest: OciDigest,
    override val size: Long,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor

internal class OciDataDescriptor(
    override val mediaType: String,
    val data: ByteArray,
    digestAlgorithm: OciDigestAlgorithm,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor {
    override val digest = data.calculateOciDigest(digestAlgorithm)
    override val size get() = data.size.toLong()
}
