package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.metadata.LAYER_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import java.io.Serializable
import java.time.Instant

/**
 * @author Silvio Giebl
 */
internal class OciComponentBuilder : Serializable {
    private var imageReference: OciImageReference? = null
    private var capabilities: Set<VersionedCoordinates> = setOf()
    private var bundleOrPlatformBundles: OciComponent.BundleOrPlatformBundles? = null
    private var indexAnnotations: Map<String, String> = mapOf()

    fun imageReference(v: OciImageReference) = apply { imageReference = v }
    fun capabilities(v: Set<VersionedCoordinates>) = apply { capabilities = v }
    fun bundleOrPlatformBundles(v: OciComponent.BundleOrPlatformBundles) = apply { bundleOrPlatformBundles = v }
    fun indexAnnotations(v: Map<String, String>) = apply { indexAnnotations = v }

    fun build() = OciComponent(
        imageReference!!,
        capabilities.toSortedSet(),
        bundleOrPlatformBundles!!,
        indexAnnotations.toSortedMap(),
    )
}

internal class OciComponentBundleBuilder : Serializable {
    private var creationTime: SerializableInstant? = null
    private var author: String? = null
    private var user: String? = null
    private var ports: Set<String> = setOf()
    private var environment: Map<String, String> = mapOf()
    private var command: OciComponent.Bundle.Command? = null
    private var volumes: Set<String> = setOf()
    private var workingDirectory: String? = null
    private var stopSignal: String? = null
    private var configAnnotations: Map<String, String> = mapOf()
    private var configDescriptorAnnotations: Map<String, String> = mapOf()
    private var manifestAnnotations: Map<String, String> = mapOf()
    private var manifestDescriptorAnnotations: Map<String, String> = mapOf()
    private var parentCapabilities: List<Coordinates> = listOf()
    private var layers: List<OciComponent.Bundle.Layer> = listOf()

    fun parentCapabilities(v: List<Coordinates>) = apply { parentCapabilities = v }
    fun creationTime(v: Instant?) = apply { creationTime = v?.toSerializableInstant() }
    fun author(v: String?) = apply { author = v }
    fun user(v: String?) = apply { user = v }
    fun ports(v: Set<String>) = apply { ports = v }
    fun environment(v: Map<String, String>) = apply { environment = v }
    fun command(v: OciComponent.Bundle.Command?) = apply { command = v }
    fun volumes(v: Set<String>) = apply { volumes = v }
    fun workingDirectory(v: String?) = apply { workingDirectory = v }
    fun stopSignal(v: String?) = apply { stopSignal = v }
    fun configAnnotations(v: Map<String, String>) = apply { configAnnotations = v }
    fun configDescriptorAnnotations(v: Map<String, String>) = apply { configDescriptorAnnotations = v }
    fun manifestAnnotations(v: Map<String, String>) = apply { manifestAnnotations = v }
    fun manifestDescriptorAnnotations(v: Map<String, String>) = apply { manifestDescriptorAnnotations = v }
    fun layers(v: List<OciComponent.Bundle.Layer>) = apply { layers = v }

    fun build() = OciComponent.Bundle(
        parentCapabilities,
        creationTime?.toInstant(),
        author,
        user,
        ports.toSortedSet(),
        environment.toSortedMap(),
        command,
        volumes.toSortedSet(),
        workingDirectory,
        stopSignal,
        configAnnotations.toSortedMap(),
        configDescriptorAnnotations.toSortedMap(),
        manifestAnnotations.toSortedMap(),
        manifestDescriptorAnnotations.toSortedMap(),
        layers,
    )
}

internal class OciComponentBundleCommandBuilder : Serializable {
    private var entryPoint: List<String>? = null
    private var arguments: List<String>? = null

    fun entryPoint(v: List<String>?) = apply { entryPoint = v }
    fun arguments(v: List<String>?) = apply { arguments = v }

    fun build() = when {
        (entryPoint == null) && (arguments == null) -> null
        else -> OciComponent.Bundle.Command(entryPoint, arguments ?: listOf())
    }
}

internal class OciComponentBundleLayerBuilder : Serializable {
    private var descriptor: OciComponent.Bundle.Layer.Descriptor? = null
    private var creationTime: SerializableInstant? = null
    private var author: String? = null
    private var createdBy: String? = null
    private var comment: String? = null

    fun descriptor(v: OciComponent.Bundle.Layer.Descriptor?) = apply { descriptor = v }
    fun creationTime(v: Instant?) = apply { creationTime = v?.toSerializableInstant() }
    fun author(v: String?) = apply { author = v }
    fun createdBy(v: String?) = apply { createdBy = v }
    fun comment(v: String?) = apply { comment = v }

    fun build() = OciComponent.Bundle.Layer(descriptor, creationTime?.toInstant(), author, createdBy, comment)
}

internal class OciComponentBundleLayerDescriptorBuilder : Serializable {
    private var digest: OciDigest? = null
    private var size: Long? = null
    private var diffId: OciDigest? = null
    private var annotations: Map<String, String> = mapOf()

    fun digest(v: OciDigest?) = apply { digest = v }
    fun size(v: Long?) = apply { size = v }
    fun diffId(v: OciDigest?) = apply { diffId = v }
    fun annotations(v: Map<String, String>) = apply { annotations = v }

    fun build() = when {
        (digest == null) && (diffId == null) && (size == null) && annotations.isEmpty() -> null
        else -> OciComponent.Bundle.Layer.Descriptor(
            LAYER_MEDIA_TYPE,
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
