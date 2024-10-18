package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.*

const val DISTRIBUTION_CATEGORY = "distribution"

val DISTRIBUTION_TYPE_ATTRIBUTE: Attribute<String> =
    Attribute.of("io.github.sgtsilvio.gradle.distributiontype", String::class.java)

const val OCI_IMAGE_DISTRIBUTION_TYPE = "oci-image"
const val OCI_IMAGE_INDEX_DISTRIBUTION_TYPE = "oci-image-index"

/**
 * | consumer value  | default compatible producer value | additional compatible producer values |
 * |-----------------|-----------------------------------|---------------------------------------|
 * | oci-image       | oci-image                         |                                       |
 * | oci-image-index | oci-image-index                   | oci-image                             |
 */
internal class OciDistributionTypeCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.consumerValue == OCI_IMAGE_INDEX_DISTRIBUTION_TYPE) {
            if (details.producerValue == OCI_IMAGE_DISTRIBUTION_TYPE) {
                details.compatible()
            }
        }
    }
}

/**
 * | consumer value  | candidates                 | closet match    |
 * |-----------------|----------------------------|-----------------|
 * | oci-image-index | oci-image-index, oci-image | oci-image-index |
 */
internal class OciDistributionTypeDisambiguationRule : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        if (details.consumerValue == OCI_IMAGE_INDEX_DISTRIBUTION_TYPE) {
            if (OCI_IMAGE_INDEX_DISTRIBUTION_TYPE in details.candidateValues) {
                details.closestMatch(OCI_IMAGE_INDEX_DISTRIBUTION_TYPE)
            }
        }
    }
}
