package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.capabilities.Capability

internal sealed interface VariantSelector {
    val capabilities: List<Capability>
    val attributes: Map<String, String>
}

internal data class ProjectVariantSelector(
    val projectPath: String,
    override val capabilities: List<Capability>,
    override val attributes: Map<String, String>,
) : VariantSelector

internal data class ExternalVariantSelector(
    val moduleId: ModuleIdentifier,
    override val capabilities: List<Capability>,
    override val attributes: Map<String, String>,
) : VariantSelector

internal fun ModuleDependency.toVariantSelector() = when (this) {
    is ProjectDependency -> ProjectVariantSelector(dependencyProject.path, requestedCapabilities, attributes.toStringMap())
    is ExternalDependency -> ExternalVariantSelector(module, requestedCapabilities, attributes.toStringMap())
    else -> throw IllegalStateException("expected ProjectDependency or ExternalDependency, got: $this")
}

internal fun ComponentSelector.toVariantSelector() = when (this) {
    is ProjectComponentSelector -> ProjectVariantSelector(projectPath, requestedCapabilities, attributes.toStringMap())
    is ModuleComponentSelector -> ExternalVariantSelector(moduleIdentifier, requestedCapabilities, attributes.toStringMap())
    else -> throw IllegalStateException("expected ProjectComponentSelector or ModuleComponentSelector, got: $this")
}