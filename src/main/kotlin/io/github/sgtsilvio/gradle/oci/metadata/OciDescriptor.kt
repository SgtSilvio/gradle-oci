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

internal class OciData(
    val mediaType: String,
    val bytes: ByteArray,
    digestAlgorithm: OciDigestAlgorithm,
) {
    val digest = bytes.calculateOciDigest(digestAlgorithm)
}

internal class OciDataDescriptor(
    val data: OciData,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor {
    override val mediaType get() = data.mediaType
    override val digest get() = data.digest
    override val size get() = data.bytes.size.toLong()
}
