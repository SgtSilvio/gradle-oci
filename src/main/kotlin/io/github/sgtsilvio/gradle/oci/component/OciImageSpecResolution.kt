package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.DEFAULT_OCI_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.attributes.MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability

class OciImageSpec(
    val platform: Platform,
    val variants: List<ResolvedVariantResult>,
    val referenceSpecs: Set<OciImageReferenceSpec>, // normalized setOf(OciImageReferenceSpec(null, null)) -> emptySet()
)

fun resolveOciImageSpecs(
    rootComponentResult: ResolvedComponentResult,
    dependencyReferenceSpecsPairs: List<Pair<ModuleDependency, List<OciImageReferenceSpec>>>,
): List<OciImageSpec> {
    val descriptorToReferenceSpecs = HashMap<ModuleDependencyDescriptor, List<OciImageReferenceSpec>>()
    for ((dependency, referenceSpecs) in dependencyReferenceSpecsPairs) {
        descriptorToReferenceSpecs.merge(dependency.toDescriptor(), referenceSpecs) { a, b -> a + b }
    }
    val rootNodesToReferenceSpecs = resolveOciVariantGraph(rootComponentResult, descriptorToReferenceSpecs)
    return resolveOciImageSpecs(rootNodesToReferenceSpecs)
}

fun resolveOciVariantGraph( // TODO private
    rootComponentResult: ResolvedComponentResult,
    descriptorToReferenceSpecs: Map<ModuleDependencyDescriptor, List<OciImageReferenceSpec>>,
): Map<OciVariantNode, Set<OciImageReferenceSpec>> {
    val nodes = HashMap<ResolvedVariantResult, OciVariantNode?>()
    val rootNodesToReferenceSpecs = LinkedHashMap<OciVariantNode, HashSet<OciImageReferenceSpec>>()
    for (dependencyResult in rootComponentResult.getDependenciesForVariant(rootComponentResult.variants.first())) {
        if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
            continue
        }
        val referenceSpecs =
            descriptorToReferenceSpecs[dependencyResult.requested.toDescriptor()] ?: listOf(DEFAULT_OCI_REFERENCE_SPEC)
        val node = resolveOciVariantNode(dependencyResult.selected, dependencyResult.resolvedVariant, nodes)
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

sealed class OciVariantNode( // TODO private
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

val ResolvedVariantResult.platformOrUniversalOrMultiple: String // TODO private
    get() {
        val platformAttribute = attributes.getAttribute(PLATFORM_ATTRIBUTE)
        if (platformAttribute != null) {
            return platformAttribute
        }
        return capabilities.first().name.substringAfterLast('@')
    }

fun resolveOciImageSpecs(rootNodesToReferenceSpecs: Map<OciVariantNode, Set<OciImageReferenceSpec>>): List<OciImageSpec> { // TODO private
    val imageSpecs = ArrayList<OciImageSpec>()
    for ((rootNode, referenceSpecs) in rootNodesToReferenceSpecs) {
        for (platform in rootNode.platformSet) {
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

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && (first() == DEFAULT_OCI_REFERENCE_SPEC)) emptySet() else this

interface ModuleDependencyDescriptor  // TODO private

private data class ProjectDependencyDescriptor(
    val projectPath: String,
    val requestedCapabilities: List<Capability>,
    val attributes: AttributeContainer,
) : ModuleDependencyDescriptor

private data class ExternalDependencyDescriptor(
    val group: String,
    val name: String,
    val requestedCapabilities: List<Capability>,
    val attributes: AttributeContainer,
) : ModuleDependencyDescriptor

private fun ModuleDependency.toDescriptor() = when (this) {
    is ProjectDependency -> ProjectDependencyDescriptor(dependencyProject.path, requestedCapabilities, attributes)
    is ExternalDependency -> ExternalDependencyDescriptor(group, name, requestedCapabilities, attributes)
    else -> throw IllegalStateException("expected ProjectDependency or ExternalDependency, got: $this")
}

private fun ComponentSelector.toDescriptor() = when (this) {
    is ProjectComponentSelector -> ProjectDependencyDescriptor(projectPath, requestedCapabilities, attributes)
    is ModuleComponentSelector -> ExternalDependencyDescriptor(group, module, requestedCapabilities, attributes)
    else -> throw IllegalStateException("expected ProjectComponentSelector or ModuleComponentSelector, got: $this")
}
