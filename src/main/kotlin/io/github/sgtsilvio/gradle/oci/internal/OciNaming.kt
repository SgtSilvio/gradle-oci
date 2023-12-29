package io.github.sgtsilvio.gradle.oci.internal

internal fun String.mainToEmpty() = if (this == "main") "" else this

internal fun createOciVariantName(variantName: String): CamelCaseString =
    variantName.mainToEmpty().toCamelCase() + "ociImage".toCamelCase()

internal fun createOciComponentClassifier(variantName: String): KebabCaseString =
    variantName.mainToEmpty().toKebabCase() + "oci-component".toKebabCase()

internal fun createOciLayerClassifier(variantName: String, layerName: String): KebabCaseString =
    variantName.mainToEmpty().toKebabCase() + layerName.toKebabCase() + "oci-layer".toKebabCase()
