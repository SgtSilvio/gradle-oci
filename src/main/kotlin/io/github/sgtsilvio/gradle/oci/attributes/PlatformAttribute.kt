package io.github.sgtsilvio.gradle.oci.attributes

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails

val PLATFORM_ATTRIBUTE: Attribute<String> = Attribute.of("io.github.sgtsilvio.gradle.platform", String::class.java)

const val UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE = "universal"
const val MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE = "multiple"

internal class PlatformAttributeCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.producerValue == UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE) {
            details.compatible()
        } else {
            details.incompatible()
        }
    }
}
