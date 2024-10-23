package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.capabilities.Capability

internal data class VariantId(
    val componentId: ComponentIdentifier,
    val capabilities: List<Capability>,
    val attributes: Map<String, String>,
)

internal fun ResolvedVariantResult.toId() = VariantId(owner, capabilities, attributes.toStringMap())