package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

internal fun resolveOciVariantGraph(rootComponent: ResolvedComponentResult): List<OciVariantGraphRoot> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponent.variants.firstOrNull() ?: return emptyList()
    val variantToNode = HashMap<ResolvedVariantResult, OciVariantNode?>()
    // rootNodesToDependencySelectors is linked to preserve the dependency order
    val rootNodesToSelectors = LinkedHashMap<OciVariantNode, HashSet<VariantSelector>>()
    for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val node = resolveOciVariantNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        rootNodesToSelectors.getOrPut(node) { HashSet() } += dependency.requested.toVariantSelector()
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
    // platformToDependenciesAndSupportedPlatforms is linked to preserve the platform order
    val platformToDependenciesAndSupportedPlatforms =
        LinkedHashMap<Platform?, Pair<ArrayList<OciVariantNode>, PlatformSet>>()
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
        val node = resolveOciVariantNode(dependency.selected, dependency.resolvedVariant, variantToNode)
        for (dependencyPlatform in dependencyPlatforms) {
            val (dependencies, supportedPlatforms) = platformToDependenciesAndSupportedPlatforms[dependencyPlatform]
                ?: throw IllegalStateException("dependency can not be defined for more platforms than component") // TODO message
            dependencies += node
            supportedPlatforms.intersect(node.supportedPlatforms)
        }
    }
    // platformToDependencies is linked to preserve the platform order
    val platformToDependencies = LinkedHashMap<Platform?, List<OciVariantNode>>()
    val platformSet = PlatformSet(false)
    for ((platform, dependenciesAndSupportedPlatforms) in platformToDependenciesAndSupportedPlatforms) {
        val (dependencies, supportedPlatforms) = dependenciesAndSupportedPlatforms
        platformToDependencies[platform] = dependencies
        platformSet.union(supportedPlatforms)
    }
    val node = OciVariantNode(variant, platformToDependencies, platformSet)
    variantToNode[variant] = node
    return node
}

internal class OciVariantNode(
    val variant: ResolvedVariantResult,
    val platformToDependencies: Map<Platform?, List<OciVariantNode>>,
    val supportedPlatforms: PlatformSet,
)

internal class OciVariantGraphRoot(val node: OciVariantNode, val variantSelectors: Set<VariantSelector>)

// return value is linked to preserve the platform order
private fun String.decodePlatforms() = split(';').mapTo(LinkedHashSet()) { it.toPlatform() }

// TODO new file from here?

internal fun selectPlatforms(
    graph: List<OciVariantGraphRoot>,
    platformSelector: PlatformSelector?,
): List<Pair<OciVariantGraphRoot, Set<Platform>>> = graph.map { graphRoot ->
    val rootNode = graphRoot.node
    val platforms = platformSelector?.select(rootNode.supportedPlatforms) ?: rootNode.supportedPlatforms.set
    if (platforms.isEmpty()) { // TODO defer exception, empty set is failed
        throw IllegalStateException("no platforms can be selected for variant ${rootNode.variant} (supported platforms: ${rootNode.supportedPlatforms}, platform selector: $platformSelector)")
    }
    Pair(graphRoot, platforms)
}

// TODO new file from here?

internal class OciImageSpec(val variants: List<ResolvedVariantResult>, val selectors: Set<VariantSelector>) // TODO

internal fun collectOciImageSpecs(rootComponent: ResolvedComponentResult): List<OciImageSpec> {
    // the first variant is the resolvable configuration, but only if it declares at least one dependency
    val rootVariant = rootComponent.variants.firstOrNull() ?: return emptyList()
    // firstLevelComponentAndVariantToSelectors is linked to preserve the dependency order
    val firstLevelComponentAndVariantToSelectors =
        LinkedHashMap<Pair<ResolvedComponentResult, ResolvedVariantResult>, HashSet<VariantSelector>>()
    for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val componentAndVariant = Pair(dependency.selected, dependency.resolvedVariant)
        firstLevelComponentAndVariantToSelectors.getOrPut(componentAndVariant) { HashSet() } += dependency.requested.toVariantSelector()
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
            if (dependency.isConstraint) continue
            if (dependency !is ResolvedDependencyResult) throw ResolutionException()
            collectOciVariants(dependency.selected, dependency.resolvedVariant, variants)
        }
        variants += variant
    }
}

internal fun Set<VariantSelector>.collectReferenceSpecs() = flatMapTo(LinkedHashSet()) { selector ->
    selector.attributes[OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE.name]?.split(',')?.map { it.toOciImageReferenceSpec() }
        ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
}.normalize()

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this

internal class ResolutionException : Exception()
