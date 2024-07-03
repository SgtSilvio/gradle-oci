package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.OciImagesInput2
import io.github.sgtsilvio.gradle.oci.attributes.MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

fun resolveOciVariantImages(rootComponentResult: ResolvedComponentResult): List<Map<Platform, List<ResolvedVariantResult>>> {
    val rootVariantNodes = resolveOciVariantGraph(rootComponentResult)
    return resolveOciVariantImages(rootVariantNodes)
}

fun resolveOciVariantGraph(rootComponentResult: ResolvedComponentResult): List<OciVariantNode> {
    val nodes = HashMap<ResolvedVariantResult, OciVariantNode?>()
    return rootComponentResult.getDependenciesForVariant(rootComponentResult.variants.first())
        .mapNotNull { dependencyResult ->
            if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
                null
            } else {
                resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
            }
        }
}

private fun resolveOciVariantNode(
    componentResult: ResolvedComponentResult,
    variantResult: ResolvedVariantResult,
    nodes: HashMap<ResolvedVariantResult, OciVariantNode?>,
): OciVariantNode {
    if (variantResult in nodes) {
        return nodes[variantResult] ?: throw IllegalStateException("cycle in dependencies graph")
    }
    nodes[variantResult] = null
    val dependencies = componentResult.getDependenciesForVariant(variantResult).mapNotNull { dependencyResult ->
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            null
        } else {
            resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
        }
    }
    val node = when (val platformOrUniversalOrMultiple = variantResult.platformOrUniversalOrMultiple) {
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
    nodes[variantResult] = node
    return node
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

fun resolveOciVariantImages(rootVariantNodes: List<OciVariantNode>): List<Map<Platform, List<ResolvedVariantResult>>> =
    rootVariantNodes.map { rootVariantNode ->
        rootVariantNode.platformSet.associateWith { platform ->
            rootVariantNode.collectVariantResultsForPlatform(platform).toList()
        }
    }

private fun OciVariantNode.collectVariantResultsForPlatform(platform: Platform): LinkedHashSet<ResolvedVariantResult> {
    val result = LinkedHashSet<ResolvedVariantResult>()
    collectVariantResultsForPlatform(platform, result)
    return result
}

private fun OciVariantNode.collectVariantResultsForPlatform(
    platform: Platform,
    result: LinkedHashSet<ResolvedVariantResult>,
) {
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

fun createImagesInput(rootVariantNodes: List<OciVariantNode>): OciImagesInput2 {
    TODO()
}
