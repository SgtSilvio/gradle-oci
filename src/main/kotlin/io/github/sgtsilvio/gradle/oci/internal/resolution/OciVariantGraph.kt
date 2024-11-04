package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

internal typealias OciVariantGraph = List<OciVariantGraphRoot>

internal class OciVariantGraphRoot(val node: OciVariantGraphNode, val variantSelectors: Set<VariantSelector>)

internal class OciVariantGraphNode(
    val variant: ResolvedVariantResult,
    val platformToDependencies: Map<Platform?, List<OciVariantGraphNode>>,
    val supportedPlatforms: PlatformSet,
)

internal fun resolveOciVariantGraph(rootComponent: ResolvedComponentResult): OciVariantGraph {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponent.variants.firstOrNull() ?: return emptyList()
    val variantToNode = HashMap<ResolvedVariantResult, OciVariantGraphNode?>()
    // rootNodesToDependencySelectors is linked to preserve the dependency order
    val rootNodesToSelectors = LinkedHashMap<OciVariantGraphNode, HashSet<VariantSelector>>()
    for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val node = resolveOciVariantGraphNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        rootNodesToSelectors.getOrPut(node) { HashSet() } += dependency.requested.toVariantSelector()
    }
    return rootNodesToSelectors.map { (node, selectors) -> OciVariantGraphRoot(node, selectors) }
}

private fun resolveOciVariantGraphNode(
    component: ResolvedComponentResult,
    variant: ResolvedVariantResult,
    variantToNode: HashMap<ResolvedVariantResult, OciVariantGraphNode?>,
): OciVariantGraphNode {
    if (variant in variantToNode) {
        return variantToNode[variant] ?: throw IllegalStateException("cycle in dependencies graph")
    }
    variantToNode[variant] = null
    // platformToDependenciesAndSupportedPlatforms is linked to preserve the platform order
    val platformToDependenciesAndSupportedPlatforms =
        LinkedHashMap<Platform?, Pair<ArrayList<OciVariantGraphNode>, PlatformSet>>()
    val platforms = variant.attributes.getAttribute(PLATFORM_ATTRIBUTE)?.decodePlatforms() ?: setOf(null)
    for (platform in platforms) {
        val supportedPlatforms = if (platform == null) PlatformSet(true) else PlatformSet(platform)
        platformToDependenciesAndSupportedPlatforms[platform] = Pair(ArrayList(), supportedPlatforms)
    }
    for (dependency in component.getDependenciesForVariant(variant)) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val dependencyPlatforms =
            dependency.requested.attributes.getAttribute(OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE)?.decodePlatforms()
                ?: platforms
        val node = resolveOciVariantGraphNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        for (dependencyPlatform in dependencyPlatforms) {
            val (dependencies, supportedPlatforms) = platformToDependenciesAndSupportedPlatforms[dependencyPlatform]
                ?: throw IllegalStateException("dependency can not be defined for more platforms than component") // TODO message
            dependencies += node
            supportedPlatforms.intersect(node.supportedPlatforms)
        }
    }
    // platformToDependencies is linked to preserve the platform order
    val platformToDependencies = LinkedHashMap<Platform?, List<OciVariantGraphNode>>()
    val platformSet = PlatformSet(false)
    for ((platform, dependenciesAndSupportedPlatforms) in platformToDependenciesAndSupportedPlatforms) {
        val (dependencies, supportedPlatforms) = dependenciesAndSupportedPlatforms
        platformToDependencies[platform] = dependencies
        platformSet.union(supportedPlatforms)
    }
    val node = OciVariantGraphNode(variant, platformToDependencies, platformSet)
    variantToNode[variant] = node
    return node
}

// return value is linked to preserve the platform order
private fun String.decodePlatforms() = split(';').mapTo(LinkedHashSet()) { it.toPlatform() }
