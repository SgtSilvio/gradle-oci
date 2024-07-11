package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.OciImageInput
import io.github.sgtsilvio.gradle.oci.OciVariantInput
import io.github.sgtsilvio.gradle.oci.internal.gradle.ArtifactViewVariantFilter
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.provider.Provider

internal fun ResolvableDependencies.resolveOciImagesInput(): Provider<List<OciImageInput>> {
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
    //  apart from performance benefits this also avoids a bug where the artifactsResultsProvider value is different when using the configuration cache
    return artifactResultsProvider.flatMap { artifactResults ->
        val imageSpecs = imageSpecsProvider.get()
        val variantDescriptorToInput = artifactResults.groupBy({ it.variant.toDescriptor() }) { it.file }
            .mapValues { (_, files) -> OciVariantInput(files.first(), files.drop(1)) }

//        val variantDescriptorToInput = HashMap<VariantDescriptor, OciVariantInput>()
//        val artifactResultsIterator = artifactResults.iterator()
//        if (artifactResultsIterator.hasNext()) {
//            var artifactResult = artifactResultsIterator.next()
//            outer@ while (true) {
//                val variantResult = artifactResult.variant
//                val metadataFile = artifactResult.file
//                val layerFiles = ArrayList<File>()
//                while (true) {
//                    if (!artifactResultsIterator.hasNext()) {
//                        break@outer
//                    }
//                    artifactResult = artifactResultsIterator.next()
//                    if (artifactResult.variant != variantResult) {
//                        break
//                    }
//                    layerFiles += artifactResult.file
//                }
//                variantDescriptorToInput[variantResult.toDescriptor()] = OciVariantInput(metadataFile, layerFiles)
//            }
//        }

//        val variantDescriptorToInput = HashMap<VariantDescriptor, OciVariantInput>()
//        val artifactResultsIterator = artifactResults.iterator()
//        if (artifactResultsIterator.hasNext()) {
//            val metadataArtifactResult = artifactResultsIterator.next()
//            var variantResult = metadataArtifactResult.variant
//            var metadataFile = metadataArtifactResult.file
//            var layerFiles = ArrayList<File>()
//            while (artifactResultsIterator.hasNext()) {
//                val artifactResult = artifactResultsIterator.next()
//                if (artifactResult.variant == variantResult) {
//                    layerFiles += artifactResult.file
//                } else {
//                    variantDescriptorToInput[variantResult.toDescriptor()] = OciVariantInput(metadataFile, layerFiles)
//                    variantResult = artifactResult.variant
//                    metadataFile = artifactResult.file
//                    layerFiles = ArrayList()
//                }
//            }
//            variantDescriptorToInput[variantResult.toDescriptor()] = OciVariantInput(metadataFile, layerFiles)
//        }

//        val variantDescriptorToInput = HashMap<VariantDescriptor, OciVariantInput>()
//        var metadataArtifactResult: ResolvedArtifactResult? = null
//        val layerFiles = ArrayList<File>()
//        for (artifactResult in artifactResults) {
//            if (artifactResult.variant == metadataArtifactResult?.variant) {
//                layerFiles += artifactResult.file
//            } else {
//                if (metadataArtifactResult != null) {
//                    variantDescriptorToInput[metadataArtifactResult.variant.toDescriptor()] =
//                        OciVariantInput(metadataArtifactResult.file, layerFiles.toList())
//                }
//                metadataArtifactResult = artifactResult
//                layerFiles.clear()
//            }
//        }

        val imageInputs = imageSpecs.map { imageSpec ->
            OciImageInput(
                imageSpec.platform,
                imageSpec.variants.mapNotNull { variant -> variantDescriptorToInput[variant.toDescriptor()] },
                imageSpec.referenceSpecs,
            )
        }
        // using map to attach the task dependencies from the artifactsResultsProvider
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
