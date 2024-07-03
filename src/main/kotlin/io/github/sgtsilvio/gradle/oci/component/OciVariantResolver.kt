package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.OciImagesInput2
import io.github.sgtsilvio.gradle.oci.attributes.MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.specs.Spec

fun resolveOciVariantGraph(rootComponentResult: ResolvedComponentResult): List<OciVariantNode> {
    val states = HashMap<ResolvedVariantResult, OciVariantNode?>()
    return rootComponentResult.getDependenciesForVariant(rootComponentResult.variants.first())
        .mapNotNull { dependencyResult ->
            if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
                null
            } else {
                resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, states)
            }
        }
}

private fun resolveOciVariantNode(
    componentResult: ResolvedComponentResult,
    variantResult: ResolvedVariantResult,
    states: HashMap<ResolvedVariantResult, OciVariantNode?>,
): OciVariantNode {
    if (variantResult in states) {
        return states[variantResult] ?: throw IllegalStateException("cycle in dependencies graph")
    }
    states[variantResult] = null
    val dependencies = componentResult.getDependenciesForVariant(variantResult).mapNotNull { dependencyResult ->
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            null
        } else {
            resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, states)
        }
    }
    val state = when (val platformOrUniversalOrMultiple = variantResult.platformOrUniversalOrMultiple) {
        MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE -> {
            val platformToDependency = HashMap<Platform, OciVariantNode.SinglePlatform>()
            val platformSet = PlatformSet(false)
            for (dependency in dependencies) {
                if (dependency !is OciVariantNode.SinglePlatform) {
                    throw IllegalStateException("dependencies of multiple platforms variant must be single platform variants")
                }
                if (platformToDependency.putIfAbsent(dependency.platform, dependency) != null) {
                    throw IllegalStateException("dependencies of multiple platforms variant must be unique single platform variants")
                }
                platformSet.union(dependency.platformSet)
            }
            OciVariantNode.MultiplePlatforms(variantResult, platformToDependency, platformSet)
        }

        UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE -> {
            val platformSet = PlatformSet(true)
            for (dependency in dependencies) {
                platformSet.intersect(dependency.platformSet)
            }
            OciVariantNode.Universal(variantResult, dependencies, platformSet)
        }

        else -> {
            val platform = platformOrUniversalOrMultiple.toPlatform()
            val platformSet = PlatformSet(platform)
            for (dependency in dependencies) {
                platformSet.intersect(dependency.platformSet)
            }
            OciVariantNode.SinglePlatform(variantResult, platform, dependencies, platformSet)
        }
    }
    states[variantResult] = state
    return state
}

sealed class OciVariantNode(
    val variantResult: ResolvedVariantResult,
    val platformSet: PlatformSet,
) {

    class MultiplePlatforms(
        variantResult: ResolvedVariantResult,
        val platformToDependency: Map<Platform, SinglePlatform>,
        platformSet: PlatformSet,
    ) : OciVariantNode(variantResult, platformSet)

    class SinglePlatform(
        variantResult: ResolvedVariantResult,
        val platform: Platform,
        val dependencies: List<OciVariantNode>,
        platformSet: PlatformSet,
    ) : OciVariantNode(variantResult, platformSet)

    class Universal(
        variantResult: ResolvedVariantResult,
        val dependencies: List<OciVariantNode>,
        platformSet: PlatformSet,
    ) : OciVariantNode(variantResult, platformSet)
}

val ResolvedVariantResult.platformOrUniversalOrMultiple: String
    get() {
        val platformAttribute = attributes.getAttribute(PLATFORM_ATTRIBUTE)
        if (platformAttribute != null) {
            return platformAttribute
        }
        return capabilities.first().name.substringAfterLast('@')
    }

fun createArtifactViewFilter(): Spec<ComponentIdentifier> {
    return Spec { componentIdentifier ->
        TODO()
    }
}

fun createImagesInput(rootVariantStates: List<OciVariantNode>): OciImagesInput2 {
    val x = rootVariantStates.map { rootVariantState ->
        rootVariantState.platformSet.associateWith { platform ->
            rootVariantState.collectVariantResultsForPlatform(platform)
        }
    }
    TODO()
}

fun OciVariantNode.collectVariantResultsForPlatform(platform: Platform): LinkedHashSet<ResolvedVariantResult> {
    val result = LinkedHashSet<ResolvedVariantResult>()
    collectVariantResultsForPlatform(platform, result)
    return result
}

fun OciVariantNode.collectVariantResultsForPlatform(platform: Platform, result: LinkedHashSet<ResolvedVariantResult>) {
    if (variantResult !in result) {
        when (this) {
            is OciVariantNode.MultiplePlatforms -> {
                platformToDependency[platform]?.collectVariantResultsForPlatform(platform, result)
                    ?: throw IllegalArgumentException("variant $variantResult does not support platform $platform (supported platforms are ${platformToDependency.keys})")
            }
            is OciVariantNode.SinglePlatform -> {
                if (platform != this.platform) {
                    throw IllegalArgumentException("variant $variantResult does not support platform $platform (supported platform is ${this.platform})")
                }
                for (dependency in dependencies) {
                    dependency.collectVariantResultsForPlatform(platform, result)
                }
            }
            is OciVariantNode.Universal -> {
                for (dependency in dependencies) {
                    dependency.collectVariantResultsForPlatform(platform, result)
                }
            }
        }
        result += variantResult
    }
}
