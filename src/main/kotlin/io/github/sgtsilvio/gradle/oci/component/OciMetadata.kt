package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.metadata.OciDescriptor
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
data class OciMetadata(
    val imageReference: OciImageReference,
    val creationTime: Instant?,
    val author: String?,
    val user: String?,
    val ports: SortedSet<String>,
    val environment: SortedMap<String, String>,
    val command: Command?,
    val volumes: SortedSet<String>,
    val workingDirectory: String?,
    val stopSignal: String?,
    val configAnnotations: SortedMap<String, String>,
    val configDescriptorAnnotations: SortedMap<String, String>,
    val manifestAnnotations: SortedMap<String, String>,
    val manifestDescriptorAnnotations: SortedMap<String, String>,
    val indexAnnotations: SortedMap<String, String>,
    val layers: List<Layer>,
) : Serializable {

    data class Command(
        val entryPoint: List<String>?, // empty (no args) is different from null (not set, inherit)
        val arguments: List<String>, // default empty
    ) : Serializable

    data class Layer(
        val descriptor: Descriptor?,
        val creationTime: Instant?,
        val author: String?,
        val createdBy: String?,
        val comment: String?,
    ) : Serializable {

        data class Descriptor(
            override val mediaType: String,
            override val digest: OciDigest,
            override val size: Long,
            val diffId: OciDigest,
            override val annotations: SortedMap<String, String>,
        ) : OciDescriptor, Serializable
    }
}