package io.github.sgtsilvio.gradle.oci.component

import java.time.Instant
import io.github.sgtsilvio.gradle.oci.internal.calculateSha256Digest

/**
 * @author Silvio Giebl
 */
data class OciComponent(
    val capabilities: Set<Capability>,
    val prebuiltIndexDigest: String?,
    val bundleOrPlatformBundles: BundleOrPlatformBundles,
) {

    data class Capability(val group: String, val name: String)

    sealed interface BundleOrPlatformBundles

    data class PlatformBundles(val map: Map<Platform, Bundle>) : BundleOrPlatformBundles

    data class Platform(
        val architecture: String,
        val os: String,
        val osVersion: String?,
        val osFeatures: List<String>,
        val variant: String?,
    )

    data class Bundle(
        val prebuiltManifestDigest: String?,
        val prebuiltConfigDigest: String?,
        val creationTime: Instant?,
        val author: String?,
        val user: String?,
        val ports: Set<String>,
        val environment: Map<String, String>,
        val command: Command?,
        val volumes: Set<String>,
        val workingDirectory: String?,
        val stopSignal: String?,
        val annotations: Map<String, String>,
        val parentCapabilities: List<Set<Capability>>,
        val layers: List<Layer>,
    ) : BundleOrPlatformBundles {

        data class Command(
            val entryPoint: List<String>?, // empty (no args) is different from null (not set, inherit)
            val arguments: List<String> // default empty
        )

        data class Layer(
            val descriptor: Descriptor?,
            val creationTime: Instant?,
            val author: String?,
            val createdBy: String?,
            val comment: String?,
        ) {

            data class Descriptor(
                override val digest: String,
                val diffId: String,
                override val size: Long,
                override val annotations: Map<String, String>,
            ) : OciComponent.Descriptor
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
    ): Descriptor {
        override val digest = calculateSha256Digest(data)
        override val size get() =  data.size.toLong()
    }
}