package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.internal.string.camelCase
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import io.github.sgtsilvio.gradle.oci.internal.string.concatKebabCase
import io.github.sgtsilvio.gradle.oci.internal.string.kebabCase
import io.github.sgtsilvio.gradle.oci.platform.Platform

internal fun String.isMain() = this == "main"

internal fun String.mainToEmpty() = if (isMain()) "" else this

internal fun createOciVariantName(imageDefName: String): String =
    imageDefName.mainToEmpty().camelCase().concatCamelCase("ociImage")

internal fun createOciVariantName(imageDefName: String, platform: Platform): String =
    createOciVariantName(imageDefName) + createPlatformPostfix(platform)

internal fun createOciVariantInternalName(imageDefName: String, platform: Platform): String =
    createOciVariantName(imageDefName).concatCamelCase("internal") + createPlatformPostfix(platform)

internal fun createOciVariantMetadataClassifier(imageDefName: String): String =
    imageDefName.mainToEmpty().kebabCase().concatKebabCase("oci-metadata")

internal fun createOciLayerClassifier(imageDefName: String, layerName: String): String =
    imageDefName.mainToEmpty().kebabCase().concatKebabCase(layerName.kebabCase()).concatKebabCase("oci-layer")

internal fun createPlatformPostfix(platform: Platform?) = if (platform == null) "" else "@$platform"
