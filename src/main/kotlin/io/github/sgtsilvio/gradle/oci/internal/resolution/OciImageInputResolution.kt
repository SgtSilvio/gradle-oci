package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.image.OciImageInput
import io.github.sgtsilvio.gradle.oci.image.OciVariantInput
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.rootDependencies
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.variantArtifacts
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.capabilities.Capability
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.listProperty

internal fun resolveOciImageInputs(
    selectedPlatformsGraph: OciVariantGraphWithSelectedPlatforms?,
    platformConfigurationPairs: List<Pair<Platform, Configuration>>,
    objectFactory: ObjectFactory,
): Provider<List<OciImageInput>> {
    val taskDependenciesProvider = objectFactory.listProperty<Any>()
    val imageInputs = ArrayList<OciImageInput>()
    val variantSelectorsToImageInput = HashMap<Pair<Platform, Set<VariantSelector>>, OciImageInput>()
    for ((platform, configuration) in platformConfigurationPairs) {
        val dependencies = configuration.incoming
        val artifacts = dependencies.artifacts
        taskDependenciesProvider.addAll(artifacts.artifactFiles.elements)
        val capabilitiesToVariantInput = artifacts.resolveOciVariantInputs()
        val imageSpecs = collectOciImageSpecs(dependencies.resolutionResult.rootDependencies.get())
        for (imageSpec in imageSpecs) {
            val imageInput = OciImageInput(
                platform,
                imageSpec.variants.mapToOciVariants(capabilitiesToVariantInput),
                imageSpec.selectors.collectReferenceSpecs(),
            )
            if (selectedPlatformsGraph == null) {
                imageInputs += imageInput
            } else {
                variantSelectorsToImageInput[Pair(platform, imageSpec.selectors)] = imageInput
            }
        }
    }
    if (selectedPlatformsGraph != null) {
        for ((graphRoot, platforms) in selectedPlatformsGraph) {
            for (platform in platforms) {
                imageInputs += variantSelectorsToImageInput[Pair(platform, graphRoot.variantSelectors)]
                    ?: throw IllegalStateException() // TODO message
            }
        }
    }
    return taskDependenciesProvider.map { imageInputs }
}

internal fun resolveOciVariantInputs(dependencies: ResolvableDependencies): Provider<List<OciVariantInput>> {
    return dependencies.resolutionResult.rootDependencies.map { rootDependencies ->
        val artifacts = dependencies.artifacts
        val taskDependenciesProvider = artifacts.artifactFiles.elements
        val capabilitiesToVariantInput = artifacts.resolveOciVariantInputs()
        val variants = collectVariants(rootDependencies)
        val variantInputs = variants.mapToOciVariants(capabilitiesToVariantInput)
        taskDependenciesProvider.map { variantInputs }
    }.flatMap { it }
}

private fun ArtifactCollection.resolveOciVariantInputs(): Map<List<Capability>, OciVariantInput> =
    variantArtifacts.groupBy({ it.capabilities }) { it.file }.mapValues { (_, files) ->
        OciVariantInput(files.first(), files.drop(1))
    }

private class OciImageSpec(val variants: LinkedHashSet<ResolvedVariantResult>, val selectors: Set<VariantSelector>)

private fun collectOciImageSpecs(rootDependencies: List<DependencyResult>): List<OciImageSpec> {
    // firstLevelComponentAndVariantToSelectors is linked to preserve the dependency order
    val firstLevelComponentAndVariantToSelectors =
        LinkedHashMap<Pair<ResolvedComponentResult, ResolvedVariantResult>, HashSet<VariantSelector>>()
    for (dependency in rootDependencies) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val componentAndVariant = Pair(dependency.selected, dependency.resolvedVariant)
        firstLevelComponentAndVariantToSelectors.getOrPut(componentAndVariant) { HashSet() } += dependency.requested.toVariantSelector()
    }
    return firstLevelComponentAndVariantToSelectors.map { (componentAndVariant, selectors) ->
        val (component, variant) = componentAndVariant
        val variants = collectVariants(component.getDependenciesForVariant(variant))
        variants += variant
        OciImageSpec(variants, selectors)
    }
}

private fun collectVariants(dependencies: List<DependencyResult>): LinkedHashSet<ResolvedVariantResult> {
    val variants = LinkedHashSet<ResolvedVariantResult>()
    collectVariants(dependencies, variants)
    return variants
}

private fun collectVariants(dependencies: List<DependencyResult>, variants: LinkedHashSet<ResolvedVariantResult>) {
    for (dependency in dependencies) {
        if (dependency.isConstraint) continue
        if (dependency !is ResolvedDependencyResult) throw ResolutionException()
        val variant = dependency.resolvedVariant
        if (variant in variants) continue
        collectVariants(dependency.selected.getDependenciesForVariant(variant), variants)
        variants += variant
    }
}

private fun LinkedHashSet<ResolvedVariantResult>.mapToOciVariants(
    capabilitiesToVariantInput: Map<List<Capability>, OciVariantInput>,
): List<OciVariantInput> = map { variant ->
    capabilitiesToVariantInput[variant.capabilities] ?: throw IllegalStateException() // TODO message
}

private fun Set<VariantSelector>.collectReferenceSpecs() = flatMapTo(LinkedHashSet()) { selector ->
    selector.attributes[OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE.name]?.split(',')?.map { it.toOciImageReferenceSpec() }
        ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
}.normalize()

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this

internal class ResolutionException : Exception()
