package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.MULTI_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

internal class OciImageSpec(
    val platform: Platform,
    val variants: List<ResolvedVariantResult>,
    val referenceSpecs: Set<OciImageReferenceSpec>, // normalized setOf(OciImageReferenceSpec(null, null)) -> emptySet()
)

internal fun resolveOciImageSpecs(
    rootComponentResult: ResolvedComponentResult,
    platformSelector: PlatformSelector?,
): List<OciImageSpec> {
    val rootNodesToReferenceSpecs = resolveOciVariantGraph(rootComponentResult)
    return collectOciImageSpecs(rootNodesToReferenceSpecs, platformSelector)
}

private fun resolveOciVariantGraph(
    rootComponentResult: ResolvedComponentResult,
): Map<OciVariantNode, Set<OciImageReferenceSpec>> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponentResult.variants.firstOrNull() ?: return emptyMap()
    val nodes = HashMap<ResolvedVariantResult, OciVariantNode?>()
    val rootNodesToReferenceSpecs = LinkedHashMap<OciVariantNode, HashSet<OciImageReferenceSpec>>()
    for (dependencyResult in rootComponentResult.getDependenciesForVariant(rootVariant)) {
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            continue
        }
        val node = resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
        val referenceSpecs = dependencyResult.requested.attributes.getAttribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE)
            ?.split(',')
            ?.map { it.toOciImageReferenceSpec() }
            ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
        rootNodesToReferenceSpecs.getOrPut(node) { HashSet() }.addAll(referenceSpecs)
    }
    return rootNodesToReferenceSpecs
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
    val node = when (val platformOrUniversalOrMulti = variantResult.platformOrUniversalOrMulti) {
        MULTI_PLATFORM_ATTRIBUTE_VALUE -> {
            val platformToDependency = HashMap<Platform, OciVariantNode.SinglePlatform>()
            val platformSet = PlatformSet(false)
            for (dependency in dependencies) {
                if (dependency !is OciVariantNode.SinglePlatform) {
                    throw IllegalStateException("dependencies of multi platform variant must be single platform variants")
                }
                if (platformToDependency.putIfAbsent(dependency.platform, dependency) != null) {
                    throw IllegalStateException("dependencies of multi platform variant must be unique single platform variants")
                }
                platformSet.union(dependency.platformSet)
            }
            OciVariantNode.MultiPlatform(variantResult, platformToDependency, platformSet)
        }

        UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE -> {
            val platformSet = PlatformSet(true)
            for (dependency in dependencies) {
                platformSet.intersect(dependency.platformSet)
            }
            OciVariantNode.Universal(variantResult, dependencies, platformSet)
        }

        else -> {
            val platform = platformOrUniversalOrMulti.toPlatform()
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

private sealed class OciVariantNode(
    val variantResult: ResolvedVariantResult,
    val platformSet: PlatformSet,
) {

    class MultiPlatform(
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

private val ResolvedVariantResult.platformOrUniversalOrMulti: String
    get() {
        val platformAttribute = attributes.getAttribute(PLATFORM_ATTRIBUTE)
        if (platformAttribute != null) {
            return platformAttribute
        }
        return capabilities.first().name.substringAfterLast('@')
    }

private fun collectOciImageSpecs(
    rootNodesToReferenceSpecs: Map<OciVariantNode, Set<OciImageReferenceSpec>>,
    platformSelector: PlatformSelector?,
): List<OciImageSpec> {
    val imageSpecs = ArrayList<OciImageSpec>()
    for ((rootNode, referenceSpecs) in rootNodesToReferenceSpecs) {
        val platforms = platformSelector?.select(rootNode.platformSet) ?: rootNode.platformSet.set
        if (platforms.isEmpty()) {
            throw IllegalStateException("no platforms can be selected for variant ${rootNode.variantResult} (supported platforms: ${rootNode.platformSet}, platform selector: $platformSelector)")
        }
        for (platform in platforms) {
            val variantResults = rootNode.collectVariantResultsForPlatform(platform).toList()
            imageSpecs += OciImageSpec(platform, variantResults, referenceSpecs.normalize())
        }
    }
    return imageSpecs
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
            is OciVariantNode.MultiPlatform -> {
                platformToDependency[platform]?.collectVariantResultsForPlatform(platform, result)
                    ?: throw IllegalArgumentException("variant $variantResult does not support platform $platform (supported platforms: ${platformToDependency.keys})")
            }

            is OciVariantNode.SinglePlatform -> {
                if (platform != this.platform) {
                    throw IllegalArgumentException("variant $variantResult does not support platform $platform (supported platform: ${this.platform})")
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

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this
