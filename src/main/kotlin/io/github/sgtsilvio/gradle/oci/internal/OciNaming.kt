package io.github.sgtsilvio.gradle.oci.internal

internal fun String.mainToEmpty() = if (this == "main") "" else this

internal fun createOciVariantName(variantName: String): String =
    variantName.mainToEmpty().camelCase().concatCamelCase("ociImage")

internal fun createOciComponentClassifier(variantName: String): String =
    variantName.mainToEmpty().kebabCase().concatKebabCase("oci-component")

internal fun createOciLayerClassifier(variantName: String, layerName: String): String =
    variantName.mainToEmpty().kebabCase().concatKebabCase(layerName.kebabCase()).concatKebabCase("oci-layer")
