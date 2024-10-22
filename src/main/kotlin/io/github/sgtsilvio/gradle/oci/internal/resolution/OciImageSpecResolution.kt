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

internal fun resolveOciVariantGraph(rootComponent: ResolvedComponentResult): List<OciVariantGraphRoot> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponent.variants.firstOrNull() ?: return emptyList()
    val variantToNode = HashMap<ResolvedVariantResult, OciVariantNode?>()
    // rootNodesToDependencySelectors is linked to preserve the dependency order
    val rootNodesToSelectors = LinkedHashMap<OciVariantNode, ArrayList<ComponentSelector>>()
    for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
        if ((dependency !is ResolvedDependencyResult) || dependency.isConstraint) {
            continue // TODO fail
        }
        val node = resolveOciVariantNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        rootNodesToSelectors.getOrPut(node) { ArrayList() }.add(dependency.requested)
    }
    return rootNodesToSelectors.map { (node, selectors) -> OciVariantGraphRoot(node, selectors) }
}

private fun resolveOciVariantNode(
    component: ResolvedComponentResult,
    variant: ResolvedVariantResult,
    variantToNode: HashMap<ResolvedVariantResult, OciVariantNode?>,
): OciVariantNode {
    if (variant in variantToNode) {
        return variantToNode[variant] ?: throw IllegalStateException("cycle in dependencies graph")
    }
    variantToNode[variant] = null
    // platformToDependenciesAndPlatformSet is linked to preserve the platform order
    val platformToDependenciesAndPlatformSet = LinkedHashMap<Platform?, Pair<ArrayList<OciVariantNode>, PlatformSet>>()
    val platforms = variant.attributes.getAttribute(PLATFORM_ATTRIBUTE)?.decodePlatforms() ?: setOf(null)
    for (platform in platforms) {
        val platformSet = if (platform == null) PlatformSet(true) else PlatformSet(platform)
        platformToDependenciesAndPlatformSet[platform] = Pair(ArrayList(), platformSet)
    }
    for (dependency in component.getDependenciesForVariant(variant)) {
        if ((dependency !is ResolvedDependencyResult) || dependency.isConstraint) {
            continue // TODO fail
        }
        val dependencyPlatforms =
            dependency.requested.attributes.getAttribute(OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE)?.decodePlatforms()
                ?: platforms
        val node = resolveOciVariantNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        for (dependencyPlatform in dependencyPlatforms) {
            val dependenciesAndPlatformSet = platformToDependenciesAndPlatformSet[dependencyPlatform]
                ?: throw IllegalStateException("dependency can not be defined for more platforms than component") // TODO message
            dependenciesAndPlatformSet.first += node
            dependenciesAndPlatformSet.second.intersect(node.platformSet)
        }
    }
    // platformToDependencies is linked to preserve the platform order
    val platformToDependencies = LinkedHashMap<Platform?, List<OciVariantNode>>()
    val platformSet = PlatformSet(false)
    for ((platform, dependenciesAndPlatformSet) in platformToDependenciesAndPlatformSet) {
        platformToDependencies[platform] = dependenciesAndPlatformSet.first
        platformSet.union(dependenciesAndPlatformSet.second)
    }
    val node = OciVariantNode(variant, platformToDependencies, platformSet)
    variantToNode[variant] = node
    return node
}

internal class OciVariantNode(
    val variant: ResolvedVariantResult,
    val platformToDependencies: Map<Platform?, List<OciVariantNode>>,
    val platformSet: PlatformSet,
)

internal class OciVariantGraphRoot(val node: OciVariantNode, val selectors: List<ComponentSelector>)

// return value is linked to preserve the platform order
private fun String.decodePlatforms() = split(';').mapTo(LinkedHashSet()) { it.toPlatform() }

// TODO new file from here?

internal fun selectPlatforms(
    graph: List<OciVariantGraphRoot>,
    platformSelector: PlatformSelector?,
): List<Pair<OciVariantGraphRoot, Set<Platform>>> = graph.map { graphRoot ->
    val rootNode = graphRoot.node
    val platforms = platformSelector?.select(rootNode.platformSet) ?: rootNode.platformSet.set
    if (platforms.isEmpty()) {
        throw IllegalStateException("no platforms can be selected for variant ${rootNode.variant} (supported platforms: ${rootNode.platformSet}, platform selector: $platformSelector)")
    }
    Pair(graphRoot, platforms)
}

// TODO new file from here?

internal class OciImageSpec(val variants: List<ResolvedVariantResult>, val selectors: List<ComponentSelector>) // TODO

internal fun collectOciImageSpecs(rootComponent: ResolvedComponentResult): List<OciImageSpec> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponent.variants.firstOrNull() ?: return emptyList()
    // firstLevelComponentAndVariantToSelectors is linked to preserve the dependency order
    val firstLevelComponentAndVariantToSelectors =
        LinkedHashMap<Pair<ResolvedComponentResult, ResolvedVariantResult>, ArrayList<ComponentSelector>>()
    for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
        if ((dependency !is ResolvedDependencyResult) || dependency.isConstraint) {
            continue // TODO fail
        }
        val componentAndVariant = Pair(dependency.selected, dependency.resolvedVariant)
        firstLevelComponentAndVariantToSelectors.getOrPut(componentAndVariant) { ArrayList() } += dependency.requested
    }
    return firstLevelComponentAndVariantToSelectors.map { (componentAndVariant, selectors) ->
        val (component, variant) = componentAndVariant
        val variants = LinkedHashSet<ResolvedVariantResult>()
        collectOciVariants(component, variant, variants)
        OciImageSpec(variants.toList(), selectors)
    }
}

private fun collectOciVariants(
    component: ResolvedComponentResult,
    variant: ResolvedVariantResult,
    variants: LinkedHashSet<ResolvedVariantResult>,
) {
    if (variant !in variants) {
        for (dependency in component.getDependenciesForVariant(variant)) {
            if ((dependency !is ResolvedDependencyResult) || dependency.isConstraint) {
                continue // TODO fail
            }
            collectOciVariants(dependency.selected, dependency.resolvedVariant, variants)
        }
        variants += variant
    }
}

internal fun List<ComponentSelector>.collectReferenceSpecs() = flatMapTo(LinkedHashSet()) { selector ->
    selector.attributes.getAttribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE)
        ?.split(',')
        ?.map { it.toOciImageReferenceSpec() } ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
}.normalize() // TODO inline?

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this
