package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.util.GradleVersion

internal sealed interface VariantSelector {
    val capabilities: Any // < 8.11 List<Capability>, >= 8.11 Set<CapabilitySelector>
    val attributes: Map<String, String>
}

private data class ProjectVariantSelector(
    val projectPath: String,
    override val capabilities: Any,
    override val attributes: Map<String, String>,
) : VariantSelector

private data class ExternalVariantSelector(
    val moduleId: ModuleIdentifier,
    override val capabilities: Any,
    override val attributes: Map<String, String>,
) : VariantSelector

private val isAtLeastGradle8Dot11 = GradleVersion.current() >= GradleVersion.version("8.11")

internal fun ModuleDependency.toVariantSelector(): VariantSelector {
    val capabilities = if (isAtLeastGradle8Dot11) capabilitySelectors else requestedCapabilities
    val attributes = attributes.toStringMap()
    return when (this) {
        is ProjectDependency -> ProjectVariantSelector(
            if (isAtLeastGradle8Dot11) path else {
                (ProjectDependency::class.java.getMethod("getDependencyProject").invoke(this) as Project).path
            },
            capabilities,
            attributes,
        )
        is ExternalDependency -> ExternalVariantSelector(module, capabilities, attributes)
        else -> throw IllegalStateException("expected ProjectDependency or ExternalDependency, got: $this")
    }
}

internal fun ComponentSelector.toVariantSelector(): VariantSelector {
    val capabilities = if (isAtLeastGradle8Dot11) capabilitySelectors else requestedCapabilities
    val attributes = attributes.toStringMap()
    return when (this) {
        is ProjectComponentSelector -> ProjectVariantSelector(projectPath, capabilities, attributes)
        is ModuleComponentSelector -> ExternalVariantSelector(moduleIdentifier, capabilities, attributes)
        else -> throw IllegalStateException("expected ProjectComponentSelector or ModuleComponentSelector, got: $this")
    }
}