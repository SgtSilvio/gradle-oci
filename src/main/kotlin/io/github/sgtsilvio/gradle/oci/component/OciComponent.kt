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
        val architecture: String,
        val os: String,
        val osVersion: String?,
        val osFeatures: List<String>,
        val variant: String?,
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

    class Builder : Serializable {
        private var capabilities: Set<Capability>? = null
        private var bundleOrPlatformBundles: BundleOrPlatformBundles? = null
        private var indexAnnotations: Map<String, String> = mapOf()

        fun capabilities(v: Set<Capability>) = apply { capabilities = v }
        fun bundleOrPlatformBundles(v: BundleOrPlatformBundles) = apply { bundleOrPlatformBundles = v }
        fun indexAnnotations(v: Map<String, String>) = apply { indexAnnotations = v }

        fun build() = OciComponent(capabilities!!, bundleOrPlatformBundles!!, indexAnnotations)
    }

    class BundleBuilder : Serializable {
        private var creationTime: Instant? = null
        private var author: String? = null
        private var user: String? = null
        private var ports: Set<String> = setOf()
        private var environment: Map<String, String> = mapOf()
        private var command: Bundle.Command? = null
        private var volumes: Set<String> = setOf()
        private var workingDirectory: String? = null
        private var stopSignal: String? = null
        private var configAnnotations: Map<String, String> = mapOf()
        private var configDescriptorAnnotations: Map<String, String> = mapOf()
        private var manifestAnnotations: Map<String, String> = mapOf()
        private var manifestDescriptorAnnotations: Map<String, String> = mapOf()
        private var parentCapabilities: List<Capability> = listOf()
        private var layers: List<Bundle.Layer> = listOf()

        fun creationTime(v: Instant?) = apply { creationTime = v }
        fun author(v: String?) = apply { author = v }
        fun user(v: String?) = apply { user = v }
        fun ports(v: Set<String>) = apply { ports = v }
        fun environment(v: Map<String, String>) = apply { environment = v }
        fun command(v: Bundle.Command?) = apply { command = v }
        fun volumes(v: Set<String>) = apply { volumes = v }
        fun workingDirectory(v: String?) = apply { workingDirectory = v }
        fun stopSignal(v: String?) = apply { stopSignal = v }
        fun configAnnotations(v: Map<String, String>) = apply { configAnnotations = v }
        fun configDescriptorAnnotations(v: Map<String, String>) = apply { configDescriptorAnnotations = v }
        fun manifestAnnotations(v: Map<String, String>) = apply { manifestAnnotations = v }
        fun manifestDescriptorAnnotations(v: Map<String, String>) = apply { manifestDescriptorAnnotations = v }
        fun parentCapabilities(v: List<Capability>) = apply { parentCapabilities = v }
        fun layers(v: List<Bundle.Layer>) = apply { layers = v }

        fun build() = Bundle(
            creationTime,
            author,
            user,
            ports,
            environment,
            command,
            volumes,
            workingDirectory,
            stopSignal,
            configAnnotations,
            configDescriptorAnnotations,
            manifestAnnotations,
            manifestDescriptorAnnotations,
            parentCapabilities,
            layers,
        )
    }

    class CommandBuilder : Serializable {
        private var entryPoint: List<String>? = null
        private var arguments: List<String>? = null

        fun entryPoint(v: List<String>?) = apply { entryPoint = v }
        fun arguments(v: List<String>?) = apply { arguments = v }

        fun build() =
            if ((entryPoint == null) && (arguments == null)) null else Bundle.Command(entryPoint, arguments ?: listOf())
    }

    class LayerBuilder : Serializable {
        private var descriptor: Bundle.Layer.Descriptor? = null
        private var creationTime: Instant? = null
        private var author: String? = null
        private var createdBy: String? = null
        private var comment: String? = null

        fun descriptor(v: Bundle.Layer.Descriptor?) = apply { descriptor = v }
        fun creationTime(v: Instant?) = apply { creationTime = v }
        fun author(v: String?) = apply { author = v }
        fun createdBy(v: String?) = apply { createdBy = v }
        fun comment(v: String?) = apply { comment = v }

        fun build() = Bundle.Layer(descriptor, creationTime, author, createdBy, comment)
    }

    class LayerDescriptorBuilder : Serializable {
        private var digest: String? = null
        private var diffId: String? = null
        private var size: Long? = null
        private var annotations: Map<String, String> = mapOf()

        fun digest(v: String?) = apply { digest = v }
        fun diffId(v: String?) = apply { diffId = v }
        fun size(v: Long?) = apply { size = v }
        fun annotations(v: Map<String, String>) = apply { annotations = v }

        fun build() = if ((digest == null) && (diffId == null) && (size == null) && (annotations.isEmpty())) null else {
            Bundle.Layer.Descriptor(digest!!, diffId!!, size!!, annotations)
        }
    }
}