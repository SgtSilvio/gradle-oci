package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.metadata.OciDescriptor
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
data class OciComponent(
    val imageReference: OciImageReference,
    val capabilities: SortedSet<VersionedCoordinates>,
    val bundleOrPlatformBundles: BundleOrPlatformBundles,
    val indexAnnotations: SortedMap<String, String>,
) : Serializable {

    sealed interface BundleOrPlatformBundles

    data class PlatformBundles(val map: SortedMap<Platform, Bundle>) : BundleOrPlatformBundles, Serializable

    data class Bundle(
        val parentCapabilities: List<Coordinates>,
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
        val layers: List<Layer>,
    ) : BundleOrPlatformBundles, Serializable {

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
}

internal val OciComponent.allLayerDescriptors: Sequence<OciComponent.Bundle.Layer.Descriptor>
    get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
        is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
    }.mapNotNull { it.descriptor }
