package io.github.sgtsilvio.gradle.oci.component

import java.time.Instant

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
        val entryPoint: List<String>?, // empty (no args) is different from null (not set)
        val arguments: List<String>?, // empty (no args) is different from null (not set)
        val volumes: Set<String>,
        val workingDirectory: String?,
        val stopSignal: String?,
        val annotations: Map<String, String>,
        val parentCapabilities: List<Set<Capability>>,
        val layers: List<Layer>,
    ) : BundleOrPlatformBundles {

        data class Layer(
            val digest: String?,
            val diffId: String?,
            val creationTime: Instant?,
            val author: String?,
            val createdBy: String?,
            val comment: String?,
        )
    }
}