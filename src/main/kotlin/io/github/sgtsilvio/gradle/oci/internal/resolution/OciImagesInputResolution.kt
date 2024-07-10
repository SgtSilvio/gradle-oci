package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.OciImageInput
import io.github.sgtsilvio.gradle.oci.OciImagesInput
import io.github.sgtsilvio.gradle.oci.OciVariantInput
import io.github.sgtsilvio.gradle.oci.internal.gradle.ArtifactViewVariantFilter
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.Provider

internal fun ResolvableDependencies.resolveOciImagesInput(): Provider<OciImagesInput> {
    val rootComponentProvider = resolutionResult.rootComponent
    val imageSpecsProvider = rootComponentProvider.map(::resolveOciImageSpecs)
    val artifactsResultsProvider = artifactView {
        componentFilter(
            ArtifactViewVariantFilter(
                rootComponentProvider,
                imageSpecsProvider.map { imageSpecs -> imageSpecs.flatMapTo(HashSet()) { it.variants } },
            )
        )
    }.artifacts.resolvedArtifacts
    // zip or map is not used here because their mapper function is executed after the file contents are available
    //  this mapper function does not read the file contents, so can already be called once the value is available
    //  this allows this mapper function to be run before storing the configuration cache
    //  apart from performance benefits this also avoids a bug where the artifactsResultsProvider value is different when using the configuration cache
    return artifactsResultsProvider.flatMap { artifactsResults ->
        val imageSpecs = imageSpecsProvider.get()
        val variantDescriptorToArtifacts = artifactsResults.groupBy({ it.variant.toDescriptor() }) { it.file }
        val variantInputs = ArrayList<OciVariantInput>(variantDescriptorToArtifacts.size)
        val variantDescriptorToIndex = HashMap<VariantDescriptor, Int>()
        var variantIndex = 0
        for ((variantDescriptor, artifacts) in variantDescriptorToArtifacts) {
            variantInputs += OciVariantInput(artifacts.first(), artifacts.drop(1))
            variantDescriptorToIndex[variantDescriptor] = variantIndex++
        }
        val imageInputs = imageSpecs.map { imageSpec ->
            OciImageInput(
                imageSpec.platform,
                imageSpec.variants.mapNotNull { variant -> variantDescriptorToIndex[variant.toDescriptor()] },
                imageSpec.referenceSpecs,
            )
        }
        val imagesInputs = OciImagesInput(variantInputs, imageInputs)
        // using map to attach the task dependencies from the artifactsResultsProvider
        artifactsResultsProvider.map { imagesInputs }
    }
}

private data class VariantDescriptor(
    val owner: ComponentIdentifier,
    val capabilities: List<Capability>,
    val attributes: Map<String, String>,
)

private fun ResolvedVariantResult.toDescriptor() = VariantDescriptor(owner, capabilities, attributes.toMap())

private fun AttributeContainer.toMap(): Map<String, String> =
    keySet().associateBy({ it.name }) { getAttribute(it).toString() }
