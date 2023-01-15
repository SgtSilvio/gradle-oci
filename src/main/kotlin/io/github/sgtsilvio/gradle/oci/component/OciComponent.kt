package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.calculateSha256Digest
import java.io.Serializable
import java.time.Instant

/**
 * @author Silvio Giebl
 */
data class OciComponent(
    val capabilities: Set<Capability>,
    val bundleOrPlatformBundles: BundleOrPlatformBundles,
    val indexAnnotations: Map<String, String>,
) : Serializable {

    data class Capability(val group: String, val name: String) : Serializable

    sealed interface BundleOrPlatformBundles

    data class PlatformBundles(val map: Map<Platform, Bundle>) : BundleOrPlatformBundles, Serializable

    data class Platform(
        val os: String,
        val architecture: String,
        val variant: String,
        val osVersion: String,
        val osFeatures: Set<String>,
    ) : Serializable

    data class Bundle(
        val creationTime: Instant?,
        val author: String?,
        val user: String?,
        val ports: Set<String>,
        val environment: Map<String, String>,
        val command: Command?,
        val volumes: Set<String>,
        val workingDirectory: String?,
        val stopSignal: String?,
        val configAnnotations: Map<String, String>,
        val configDescriptorAnnotations: Map<String, String>,
        val manifestAnnotations: Map<String, String>,
        val manifestDescriptorAnnotations: Map<String, String>,
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
                override val annotations: Map<String, String>,
            ) : OciComponent.Descriptor, Serializable
        }
    }

    interface Descriptor {
        val digest: String
        val size: Long
        val annotations: Map<String, String>
    }

    class DataDescriptor(
        val data: ByteArray,
        override val annotations: Map<String, String>,
    ) : Descriptor {
        override val digest = calculateSha256Digest(data)
        override val size get() = data.size.toLong()
    }
}