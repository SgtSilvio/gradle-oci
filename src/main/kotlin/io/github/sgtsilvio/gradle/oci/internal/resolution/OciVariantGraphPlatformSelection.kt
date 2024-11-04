package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector

internal typealias OciVariantGraphWithSelectedPlatforms = List<Pair<OciVariantGraphRoot, Set<Platform>>>

internal fun OciVariantGraph.selectPlatforms(platformSelector: PlatformSelector?): OciVariantGraphWithSelectedPlatforms {
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

internal fun OciVariantGraphWithSelectedPlatforms.groupByPlatform(): Map<Platform, List<OciVariantGraphRoot>> {
    val platformToGraphRoots = HashMap<Platform, ArrayList<OciVariantGraphRoot>>()
    for ((graphRoot, platforms) in this) {
        for (platform in platforms) {
            platformToGraphRoots.getOrPut(platform) { ArrayList() } += graphRoot
        }
    }
    return platformToGraphRoots
}
