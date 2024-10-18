package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute

val PLATFORM_ATTRIBUTE: Attribute<String> = Attribute.of("io.github.sgtsilvio.gradle.platform", String::class.java)
