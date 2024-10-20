package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

internal fun resolveOciVariantGraph(rootComponentResult: ResolvedComponentResult): List<OciVariantGraphRoot> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariantResult = rootComponentResult.variants.firstOrNull() ?: return emptyList()
    val nodes = HashMap<ResolvedVariantResult, OciVariantNode?>()
    val rootNodesToDependencySelectors = LinkedHashMap<OciVariantNode, ArrayList<ComponentSelector>>()
    for (dependencyResult in rootComponentResult.getDependenciesForVariant(rootVariantResult)) {
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            continue // TODO fail
        }
        val node = resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
        rootNodesToDependencySelectors.getOrPut(node) { ArrayList() }.add(dependencyResult.requested)
    }
    return rootNodesToDependencySelectors.map { (node, dependencySelectors) ->
        OciVariantGraphRoot(node, dependencySelectors)
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
    val platformToDependencies = LinkedHashMap<Platform?, Pair<ArrayList<OciVariantNode>, PlatformSet>>()
    val platforms = variantResult.attributes.getAttribute(PLATFORM_ATTRIBUTE)?.decodePlatforms() ?: setOf(null)
    for (platform in platforms) {
        val platformSet = if (platform == null) PlatformSet(true) else PlatformSet(platform)
        platformToDependencies[platform] = Pair(ArrayList(), platformSet)
    }
    for (dependencyResult in componentResult.getDependenciesForVariant(variantResult)) {
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            continue // TODO fail
        }
        val dependencyPlatforms =
            dependencyResult.requested.attributes.getAttribute(OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE)?.decodePlatforms()
                ?: platforms
        val node = resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
        for (dependencyPlatform in dependencyPlatforms) {
            val dependenciesAndPlatformSet = platformToDependencies[dependencyPlatform]
                ?: throw IllegalStateException("dependency can not be defined for more platforms than component") // TODO message
            val (dependencies, dependencyPlatformSet) = dependenciesAndPlatformSet
            dependencies += node
            dependencyPlatformSet.intersect(node.platformSet)
        }
    }
    val platformSet = PlatformSet(false)
    for ((_, dependenciesAndPlatformSet) in platformToDependencies) {
        platformSet.union(dependenciesAndPlatformSet.second)
    }
    val node = OciVariantNode(variantResult, platformToDependencies.mapValues { it.value.first }, platformSet)
    nodes[variantResult] = node
    return node
}

internal class OciVariantNode(
    val variantResult: ResolvedVariantResult,
    val platformToDependencies: Map<Platform?, List<OciVariantNode>>,
    val platformSet: PlatformSet,
)

internal class OciVariantGraphRoot(val node: OciVariantNode, val dependencySelectors: List<ComponentSelector>)

private fun String.decodePlatforms() = split(';').mapTo(LinkedHashSet()) { it.toPlatform() }

// TODO new file from here?

internal fun selectPlatforms(
    graph: List<OciVariantGraphRoot>,
    platformSelector: PlatformSelector?,
): Map<Platform, List<OciVariantGraphRoot>> {
    val platformToGraphRoots = HashMap<Platform, ArrayList<OciVariantGraphRoot>>()
    for (graphRoot in graph) {
        val rootNode = graphRoot.node
        val platforms = platformSelector?.select(rootNode.platformSet) ?: rootNode.platformSet.set
        if (platforms.isEmpty()) {
            throw IllegalStateException("no platforms can be selected for variant ${rootNode.variantResult} (supported platforms: ${rootNode.platformSet}, platform selector: $platformSelector)")
        }
        for (platform in platforms) {
            platformToGraphRoots.getOrPut(platform) { ArrayList() } += graphRoot
        }
    }
    return platformToGraphRoots
}

// TODO new file from here?

internal class OciImageSpec(
    val platform: Platform,
    val variants: List<ResolvedVariantResult>,
    val referenceSpecs: Set<OciImageReferenceSpec>, // normalized setOf(OciImageReferenceSpec(null, null)) -> emptySet()
)

internal fun collectOciImageSpecs(rootComponentResult: ResolvedComponentResult, platform: Platform): List<OciImageSpec> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariantResult = rootComponentResult.variants.firstOrNull() ?: return emptyList()
    // TODO naming firstLevel...
    val variantToReferenceSpecs = LinkedHashMap<Pair<ResolvedComponentResult, ResolvedVariantResult>, HashSet<OciImageReferenceSpec>>()
    for (dependencyResult in rootComponentResult.getDependenciesForVariant(rootVariantResult)) {
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            continue // TODO fail
        }
        val referenceSpecs = dependencyResult.requested.attributes.getAttribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE)
            ?.split(',')
            ?.map { it.toOciImageReferenceSpec() }
            ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
        variantToReferenceSpecs.getOrPut(Pair(dependencyResult.selected, dependencyResult.resolvedVariant)) { HashSet() } += referenceSpecs
    }
    return variantToReferenceSpecs.map { (variant, referenceSpecs) ->
        val (componentResult, variantResult) = variant
        val variantResults = LinkedHashSet<ResolvedVariantResult>()
        collectOciVariantResults(componentResult, variantResult, variantResults)
        OciImageSpec(platform, variantResults.toList(), referenceSpecs.normalize())
    }
}

private fun collectOciVariantResults(
    componentResult: ResolvedComponentResult,
    variantResult: ResolvedVariantResult,
    variantResults: LinkedHashSet<ResolvedVariantResult>,
) {
    if (variantResult !in variantResults) {
        for (dependencyResult in componentResult.getDependenciesForVariant(variantResult)) {
            if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
                continue // TODO fail
            }
            collectOciVariantResults(dependencyResult.selected, dependencyResult.resolvedVariant, variantResults)
        }
        variantResults += variantResult
    }
}

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this
