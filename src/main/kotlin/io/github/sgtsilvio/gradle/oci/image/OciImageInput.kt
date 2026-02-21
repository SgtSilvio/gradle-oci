package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
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
