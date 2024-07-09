package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute

val DISTRIBUTION_TYPE_ATTRIBUTE: Attribute<String> =
    Attribute.of("io.github.sgtsilvio.gradle.distributiontype", String::class.java)

const val DISTRIBUTION_CATEGORY = "distribution"

const val OCI_IMAGE_DISTRIBUTION_TYPE = "oci-image"
