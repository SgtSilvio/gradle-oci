package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.decodeAsJsonToOciMetadata
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.tasks.*
import java.io.File

data class OciImageInput(
    @get:Input val platform: Platform,
    @get:Nested val variants: List<OciVariantInput>,
    @get:Input val referenceSpecs: Set<OciImageReferenceSpec>,
) {
    init {
        require(variants.isNotEmpty()) { "variants must not be empty" }
    }
}

data class OciVariantInput(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val metadataFile: File,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val layerFiles: List<File>,
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
