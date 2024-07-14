package io.github.sgtsilvio.gradle.oci.metadata

import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
class OciVariantMetadata(
    val imageReference: OciImageReference,
    val creationTime: Instant?,
    val author: String?,
    val user: String?,
    val ports: SortedSet<String>,
    val environment: SortedMap<String, String>,
    val entryPoint: List<String>?, // empty (no args) is different from null (not set, inherit)
    val arguments: List<String>?, // empty (no args) is different from null (not set, inherit)
    val volumes: SortedSet<String>,
    val workingDirectory: String?,
    val stopSignal: String?,
    val configAnnotations: SortedMap<String, String>,
    val configDescriptorAnnotations: SortedMap<String, String>,
    val manifestAnnotations: SortedMap<String, String>,
    val manifestDescriptorAnnotations: SortedMap<String, String>,
    val indexAnnotations: SortedMap<String, String>,
    val layers: List<OciLayerMetadata>,
)

class OciLayerMetadata(
    val descriptor: OciLayerDescriptor?,
    val creationTime: Instant?,
    val author: String?,
    val createdBy: String?,
    val comment: String?,
)

class OciLayerDescriptor(
    override val mediaType: String,
    override val digest: OciDigest,
    override val size: Long,
    val diffId: OciDigest,
    override val annotations: SortedMap<String, String>,
) : OciDescriptor
