package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute

internal val OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE: Attribute<String> =
    Attribute.of("io.github.sgtsilvio.gradle.oci.image.reference.specs", String::class.java)
