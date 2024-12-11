package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.metadata.OciData
import io.github.sgtsilvio.gradle.oci.metadata.OciDataDescriptor
import io.github.sgtsilvio.gradle.oci.metadata.OciLayerDescriptor
import io.github.sgtsilvio.gradle.oci.metadata.OciMetadata
import io.github.sgtsilvio.gradle.oci.platform.Platform
import java.io.File

class OciMultiPlatformImage(
    val index: OciData,
    val platformToImage: Map<Platform, OciImage>,
)

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

class OciVariant(
    val metadata: OciMetadata,
    val layers: List<OciLayer>,
)

class OciLayer(
    val descriptor: OciLayerDescriptor,
    val file: File,
)
