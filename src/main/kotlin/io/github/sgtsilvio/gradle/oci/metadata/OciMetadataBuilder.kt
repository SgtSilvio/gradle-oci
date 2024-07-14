package io.github.sgtsilvio.gradle.oci.metadata

import java.io.Serializable
import java.time.Instant

/**
 * @author Silvio Giebl
 */
internal class OciMetadataBuilder : Serializable {
    private var imageReference: OciImageReference? = null
    private var creationTime: SerializableInstant? = null
    private var author: String? = null
    private var user: String? = null
    private var ports: Set<String> = emptySet()
    private var environment: Map<String, String> = emptyMap()
    private var entryPoint: List<String>? = null
    private var arguments: List<String>? = null
    private var volumes: Set<String> = emptySet()
    private var workingDirectory: String? = null
    private var stopSignal: String? = null
    private var configAnnotations: Map<String, String> = emptyMap()
    private var configDescriptorAnnotations: Map<String, String> = emptyMap()
    private var manifestAnnotations: Map<String, String> = emptyMap()
    private var manifestDescriptorAnnotations: Map<String, String> = emptyMap()
    private var indexAnnotations: Map<String, String> = emptyMap()
    private var layers: List<OciLayerMetadata> = emptyList()

    fun imageReference(v: OciImageReference) = apply { imageReference = v }
    fun creationTime(v: Instant?) = apply { creationTime = v?.toSerializableInstant() }
    fun author(v: String?) = apply { author = v }
    fun user(v: String?) = apply { user = v }
    fun ports(v: Set<String>) = apply { ports = v }
    fun environment(v: Map<String, String>) = apply { environment = v }
    fun entryPoint(v: List<String>?) = apply { entryPoint = v }
    fun arguments(v: List<String>?) = apply { arguments = v }
    fun volumes(v: Set<String>) = apply { volumes = v }
    fun workingDirectory(v: String?) = apply { workingDirectory = v }
    fun stopSignal(v: String?) = apply { stopSignal = v }
    fun configAnnotations(v: Map<String, String>) = apply { configAnnotations = v }
    fun configDescriptorAnnotations(v: Map<String, String>) = apply { configDescriptorAnnotations = v }
    fun manifestAnnotations(v: Map<String, String>) = apply { manifestAnnotations = v }
    fun manifestDescriptorAnnotations(v: Map<String, String>) = apply { manifestDescriptorAnnotations = v }
    fun indexAnnotations(v: Map<String, String>) = apply { indexAnnotations = v }
    fun layers(v: List<OciLayerMetadata>) = apply { layers = v }

    fun build() = OciMetadata(
        imageReference!!,
        creationTime?.toInstant(),
        author,
        user,
        ports.toSortedSet(),
        environment.toSortedMap(),
        entryPoint,
        arguments,
        volumes.toSortedSet(),
        workingDirectory,
        stopSignal,
        configAnnotations.toSortedMap(),
        configDescriptorAnnotations.toSortedMap(),
        manifestAnnotations.toSortedMap(),
        manifestDescriptorAnnotations.toSortedMap(),
        indexAnnotations.toSortedMap(),
        layers,
    )
}

internal class OciLayerMetadataBuilder : Serializable {
    private var descriptor: OciLayerDescriptor? = null
    private var creationTime: SerializableInstant? = null
    private var author: String? = null
    private var createdBy: String? = null
    private var comment: String? = null

    fun descriptor(v: OciLayerDescriptor?) = apply { descriptor = v }
    fun creationTime(v: Instant?) = apply { creationTime = v?.toSerializableInstant() }
    fun author(v: String?) = apply { author = v }
    fun createdBy(v: String?) = apply { createdBy = v }
    fun comment(v: String?) = apply { comment = v }

    fun build() = OciLayerMetadata(descriptor, creationTime?.toInstant(), author, createdBy, comment)
}

internal class OciLayerDescriptorBuilder : Serializable {
    private var mediaType: String? = null
    private var digest: OciDigest? = null
    private var size: Long? = null
    private var diffId: OciDigest? = null
    private var annotations: Map<String, String> = emptyMap()

    fun mediaType(v: String?) = apply { mediaType = v }
    fun digest(v: OciDigest?) = apply { digest = v }
    fun size(v: Long?) = apply { size = v }
    fun diffId(v: OciDigest?) = apply { diffId = v }
    fun annotations(v: Map<String, String>) = apply { annotations = v }

    fun build() = when {
        (mediaType == null) && (digest == null) && (size == null) && (diffId == null) && annotations.isEmpty() -> null
        else -> OciLayerDescriptor(
            mediaType!!,
            digest!!,
            size!!,
            diffId!!,
            annotations.toSortedMap(),
        )
    }
}

private class SerializableInstant(val epochSecond: Long, val nano: Int) : Serializable

private fun Instant.toSerializableInstant() = SerializableInstant(epochSecond, nano)

private fun SerializableInstant.toInstant(): Instant = Instant.ofEpochSecond(epochSecond, nano.toLong())
