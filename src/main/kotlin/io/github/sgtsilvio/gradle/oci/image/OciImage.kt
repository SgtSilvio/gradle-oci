package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.io.File

class OciMultiPlatformImage(
    val index: OciData,
    val platformToImage: Map<Platform, OciImage>,
)

internal fun OciMultiPlatformImage(images: Map<Platform, OciImage>): OciMultiPlatformImage {
    val index = createIndex(images.values)
    return OciMultiPlatformImage(index, images)
}

class OciImage(
    val manifest: OciDataDescriptor,
    val config: OciDataDescriptor,
    val platform: Platform,
    val variants: List<OciVariant>,
) {
    init {
        require(variants.isNotEmpty()) { "variants must not be empty" }
    }
}

internal fun OciImage(platform: Platform, variants: List<OciVariant>): OciImage {
    val config = createConfig(platform, variants)
    val manifest = createManifest(config, variants)
    return OciImage(manifest, config, platform, variants)
}

class OciVariant(
    val metadata: OciMetadata,
    val layers: List<OciLayer>,
)

internal fun OciVariantInput.toVariant(): OciVariant {
    val metadata = metadataFile.readText().decodeAsJsonToOciMetadata()
    val layerDescriptors = metadata.layers.mapNotNull { it.descriptor }
    if (layerDescriptors.size != layerFiles.size) {
        throw IllegalStateException("count of layer descriptors (${layerDescriptors.size}) and layer files (${layerFiles.size}) do not match")
    }
    val layers = layerDescriptors.zip(layerFiles) { descriptor, file -> OciLayer(descriptor, file) }
    return OciVariant(metadata, layers)
}

class OciLayer(
    val descriptor: OciLayerDescriptor,
    val file: File,
)
