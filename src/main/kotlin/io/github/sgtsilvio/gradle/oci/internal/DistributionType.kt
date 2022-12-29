package io.github.sgtsilvio.gradle.oci.internal

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

/**
 * @author Silvio Giebl
 */
interface DistributionType : Named

val DISTRIBUTION_TYPE_ATTRIBUTE =
    Attribute.of("io.github.sgtsilvio.gradle.distributiontype", DistributionType::class.java)