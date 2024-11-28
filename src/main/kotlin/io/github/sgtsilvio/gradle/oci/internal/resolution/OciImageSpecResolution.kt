package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.variantArtifacts
import io.github.sgtsilvio.gradle.oci.metadata.DEFAULT_OCI_IMAGE_REFERENCE_SPEC
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.metadata.toOciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.listProperty

internal fun resolveOciImageInputs(
    selectedPlatformsGraph: OciVariantGraphWithSelectedPlatforms?,
    platformConfigurationPairs: List<Pair<Platform, Configuration>>,
    objectFactory: ObjectFactory,
): Provider<List<OciImagesTask.ImageInput>> {
    val taskDependenciesProvider = objectFactory.listProperty<Any>()
    val imageInputs = ArrayList<OciImagesTask.ImageInput>()
    val variantSelectorsToImageInput = HashMap<Pair<Platform, Set<VariantSelector>>, OciImagesTask.ImageInput>()
    for ((platform, configuration) in platformConfigurationPairs) {
        val artifacts = configuration.incoming.artifacts
        taskDependenciesProvider.addAll(artifacts.resolvedArtifacts)
        val capabilitiesToVariantInput = artifacts.variantArtifacts.groupBy({ it.capabilities }) { it.file }
            .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
        val imageSpecs = collectOciImageSpecs(configuration.incoming.resolutionResult.root)
        for (imageSpec in imageSpecs) {
            val imageInput = OciImagesTask.ImageInput(
                platform,
                imageSpec.variants.map { variant ->
                    capabilitiesToVariantInput[variant.capabilities] ?: throw IllegalStateException() // TODO message
                },
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

private class OciImageSpec(val variants: List<ResolvedVariantResult>, val selectors: Set<VariantSelector>)

private fun collectOciImageSpecs(rootComponent: ResolvedComponentResult): List<OciImageSpec> {
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

private fun Set<VariantSelector>.collectReferenceSpecs() = flatMapTo(LinkedHashSet()) { selector ->
    selector.attributes[OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE.name]?.split(',')?.map { it.toOciImageReferenceSpec() }
        ?: listOf(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)
}.normalize()

private fun Set<OciImageReferenceSpec>.normalize(): Set<OciImageReferenceSpec> =
    if ((size == 1) && contains(DEFAULT_OCI_IMAGE_REFERENCE_SPEC)) emptySet() else this

internal class ResolutionException : Exception()
