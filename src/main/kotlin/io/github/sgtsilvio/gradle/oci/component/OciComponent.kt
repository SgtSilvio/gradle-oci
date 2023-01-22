package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.dsl.Platform
import java.io.Serializable
import java.time.Instant
import java.util.*

/**
 * @author Silvio Giebl
 */
data class OciComponent(
    val capabilities: SortedSet<Capability>,
    val bundleOrPlatformBundles: BundleOrPlatformBundles,
    val indexAnnotations: SortedMap<String, String>,
) : Serializable {

    data class Capability(val group: String, val name: String) : Comparable<Capability>, Serializable {
        override fun compareTo(other: Capability): Int {
            group.compareTo(other.group).also { if (it != 0) return it }
            return name.compareTo(other.name)
        }
    }

    sealed interface BundleOrPlatformBundles

    data class PlatformBundles(val map: SortedMap<Platform, Bundle>) : BundleOrPlatformBundles, Serializable

    data class Bundle(
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
        val parentCapabilities: List<Capability>,
        val layers: List<Layer>,
    ) : BundleOrPlatformBundles, Serializable {

        data class Command(
            val entryPoint: List<String>?, // empty (no args) is different from null (not set, inherit)
            val arguments: List<String> // default empty
        ) : Serializable

        data class Layer(
            val descriptor: Descriptor?,
            val creationTime: Instant?,
            val author: String?,
            val createdBy: String?,
            val comment: String?,
        ) : Serializable {

            data class Descriptor(
                override val digest: String,
                val diffId: String,
                override val size: Long,
                override val annotations: SortedMap<String, String>,
            ) : OciDescriptor, Serializable
        }
    }
}