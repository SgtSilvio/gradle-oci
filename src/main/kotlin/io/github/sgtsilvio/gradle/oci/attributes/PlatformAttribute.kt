package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute

val PLATFORM_ATTRIBUTE: Attribute<String> = Attribute.of("io.github.sgtsilvio.gradle.platform", String::class.java)

const val UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE = "universal"
const val MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE = "multiple"
