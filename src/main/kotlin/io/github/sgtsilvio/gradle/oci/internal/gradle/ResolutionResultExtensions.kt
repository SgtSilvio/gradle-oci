package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.provider.Provider

val ResolutionResult.rootDependencies: Provider<List<DependencyResult>>
    get() {
        return rootComponent.map { rootComponent ->
            // the first variant is the resolvable configuration, but only if it declares at least one dependency
            val rootVariant = rootComponent.variants.firstOrNull()
            if (rootVariant == null) emptyList() else rootComponent.getDependenciesForVariant(rootVariant)
        }
        // TODO use rootVariant on newer gradle versions
//        return rootComponent.zip(rootVariant) { rootComponent, rootVariant ->
//            rootComponent.getDependenciesForVariant(rootVariant)
//        }
    }
