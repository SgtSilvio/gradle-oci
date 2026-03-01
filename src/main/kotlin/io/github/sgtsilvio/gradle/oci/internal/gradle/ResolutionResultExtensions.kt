package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.provider.Provider
import org.gradle.util.GradleVersion

internal val ResolutionResult.rootDependencies: Provider<List<DependencyResult>>
    get() {
        return if (GradleVersion.current() >= GradleVersion.version("8.11")) {
            rootComponent.zip(rootVariant) { rootComponent, rootVariant ->
                rootComponent.getDependenciesForVariant(rootVariant)
            }
        } else {
            rootComponent.map { rootComponent ->
                // the first variant is the resolvable configuration, but only if it declares at least one dependency
                val rootVariant = rootComponent.variants.firstOrNull()
                if (rootVariant == null) emptyList() else rootComponent.getDependenciesForVariant(rootVariant)
            }
        }
    }
