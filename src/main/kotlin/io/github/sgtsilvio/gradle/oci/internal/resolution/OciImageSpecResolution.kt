package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult

// TODO new file from here?

internal fun OciVariantGraph.selectPlatforms(
    platformSelector: PlatformSelector?,
): List<Pair<OciVariantGraphRoot, Set<Platform>>> {
    val selectedPlatformsGraph = ArrayList<Pair<OciVariantGraphRoot, Set<Platform>>>(size)
    val graphRootsWithEmptySelection = ArrayList<OciVariantGraphRoot>()
    for (graphRoot in this) {
        val supportedPlatforms = graphRoot.node.supportedPlatforms
        val platforms = platformSelector?.select(supportedPlatforms) ?: supportedPlatforms.set
        if (platforms.isEmpty()) {
            graphRootsWithEmptySelection += graphRoot
        } else {
            selectedPlatformsGraph += Pair(graphRoot, platforms)
        }
    }
    if (graphRootsWithEmptySelection.isNotEmpty()) {
        val errorMessage = graphRootsWithEmptySelection.joinToString("\n") {
            "no platforms can be selected for variant ${it.node.variant} (supported platforms: ${it.node.supportedPlatforms}, platform selector: $platformSelector)"
        }
        throw IllegalStateException(errorMessage)
    }
    return selectedPlatformsGraph
}

internal fun List<Pair<OciVariantGraphRoot, Set<Platform>>>.groupByPlatform(): Map<Platform, List<OciVariantGraphRoot>> {
    val platformToGraphRoots = HashMap<Platform, ArrayList<OciVariantGraphRoot>>()
    for ((graphRoot, platforms) in this) {
        for (platform in platforms) {
            platformToGraphRoots.getOrPut(platform) { ArrayList() } += graphRoot
        }
    }
    return platformToGraphRoots
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
