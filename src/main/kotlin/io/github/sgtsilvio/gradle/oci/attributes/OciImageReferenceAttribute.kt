package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute

val OCI_IMAGE_REFERENCE_ATTRIBUTE: Attribute<String> =
    Attribute.of("io.github.sgtsilvio.gradle.oci.image.reference", String::class.java)
