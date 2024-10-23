package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.attributes.AttributeContainer

internal fun AttributeContainer.toStringMap(): Map<String, String> =
    keySet().associateBy({ it.name }) { getAttribute(it).toString() }
