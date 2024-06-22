package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.platform.Platform

internal fun String.isMain() = this == "main"

internal fun String.mainToEmpty() = if (isMain()) "" else this

internal fun createOciVariantName(variantName: String): String =
    variantName.mainToEmpty().camelCase().concatCamelCase("ociImage")

internal fun createOciVariantName(variantName: String, platform: Platform): String =
    variantName.mainToEmpty().camelCase().concatCamelCase("ociImage") + platform

internal fun createOciVariantInternalName(variantName: String, platform: Platform): String =
    variantName.mainToEmpty().camelCase().concatCamelCase("ociImageInternal") + platform

internal fun createOciMetadataClassifier(variantName: String): String =
    variantName.mainToEmpty().kebabCase().concatKebabCase("oci-metadata")

internal fun createOciLayerClassifier(variantName: String, layerName: String): String =
    variantName.mainToEmpty().kebabCase().concatKebabCase(layerName.kebabCase()).concatKebabCase("oci-layer")
