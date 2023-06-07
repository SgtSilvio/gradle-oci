package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

/**
 * @author Silvio Giebl
 */
interface DistributionType : Named

val DISTRIBUTION_TYPE_ATTRIBUTE: Attribute<DistributionType> =
    Attribute.of("io.github.sgtsilvio.gradle.distributiontype", DistributionType::class.java)

const val DISTRIBUTION_CATEGORY = "distribution"

const val OCI_IMAGE_DISTRIBUTION_TYPE = "oci-image"