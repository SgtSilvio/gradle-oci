package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.internal.gradle.ArtifactViewVariantFilter
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.Provider

internal fun ResolvableDependencies.resolveOciImageInputs(): Provider<List<OciImagesTask.ImageInput>> {
    val rootComponentProvider = resolutionResult.rootComponent
    val imageSpecsProvider = rootComponentProvider.map(::resolveOciImageSpecs)
    val artifactResultsProvider = artifactView {
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
    //  apart from performance benefits this also avoids a bug where the artifactResultsProvider value is different when using the configuration cache
    return artifactResultsProvider.flatMap { artifactResults ->
        val imageSpecs = imageSpecsProvider.get()
        val variantDescriptorToInput = artifactResults.groupBy({ it.variant.toDescriptor() }) { it.file }
            .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
        val imageInputs = imageSpecs.map { imageSpec ->
            OciImagesTask.ImageInput(
                imageSpec.platform,
                imageSpec.variants.mapNotNull { variant -> variantDescriptorToInput[variant.toDescriptor()] },
                imageSpec.referenceSpecs,
            )
        }
        // using map to attach the task dependencies from the artifactResultsProvider
        artifactResultsProvider.map { imageInputs }
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
