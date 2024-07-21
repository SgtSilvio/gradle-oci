package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.internal.gradle.ArtifactViewVariantFilter
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.artifacts.configurations.ArtifactCollectionInternal
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.provider.Provider
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ImmutableCapabilities
import java.io.File

internal fun ResolvableDependencies.resolveOciImageInputs(
    platformSelectorProvider: Provider<PlatformSelector>,
): Provider<List<OciImagesTask.ImageInput>> {
    val rootComponentResultProvider = resolutionResult.rootComponent
    val imageSpecsProvider =
        rootComponentResultProvider.zipAbsentAsNull(platformSelectorProvider, ::resolveOciImageSpecs)
    val artifacts = artifactView {
        componentFilter(
            ArtifactViewVariantFilter(
                rootComponentResultProvider,
                imageSpecsProvider.map { imageSpecs -> imageSpecs.flatMapTo(HashSet()) { it.variants } },
            )
        )
    }.artifacts
    /*
    val artifactResultsProvider = artifacts.resolvedArtifacts
    val variantArtifactResultsProvider = providerFactory.provider {
        val variantArtifactResults = LinkedHashSet<VariantArtifactResult>()
        (artifacts as ArtifactCollectionInternal).visitArtifacts(object : ArtifactVisitor {
            override fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: ImmutableCapabilities,
                artifact: ResolvableArtifact,
            ) {
                @Suppress("UNCHECKED_CAST") val capabilitySet =
                    ImmutableCapabilities::class.java.getMethod("asSet").invoke(capabilities) as Set<Capability>
                visitArtifact(variantName, attributes, capabilitySet.toList(), artifact)
            }

            @Suppress("UNUSED_PARAMETER")
            fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: List<Capability>,
                artifact: ResolvableArtifact,
            ) {
                variantArtifactResults += VariantArtifactResult(
                    VariantDescriptor(artifact.id.componentIdentifier, capabilities, attributes.toMap()),
                    artifact.file,
                )
            }

            override fun visitFailure(failure: Throwable) = Unit

            override fun requireArtifactFiles() = true
        })
        variantArtifactResults
    }
    // zip or map is not used here because their mapper function is executed after the file contents are available
    //  this mapper function does not read the file contents, so can already be called once the value is available
    //  this allows this mapper function to be run before storing the configuration cache
    //  apart from performance benefits this also avoids a bug where the artifactResultsProvider value is different when using the configuration cache
//    return artifactResultsProvider.flatMap { artifactResults ->
    return variantArtifactResultsProvider.flatMap { variantArtifactResults ->
//        for (artifactResult in artifactResults) {
//            println(artifactResult.variant.toString() + " " + artifactResult.file + " " + artifactResult::class.java + " " + artifactResult.id + " " + artifactResult.id::class.java + " " + artifactResult.id.hashCode())
//            val id = artifactResult.id
//            if (id is PublishArtifactLocalArtifactMetadata) {
//                println(id.toString() + " " + id.componentIdentifier::class.java + " " + id.componentIdentifier.hashCode() + " " + id.publishArtifact::class.java + " " + id.publishArtifact.hashCode())
//            }
//        }
//        println()
//        for (variantArtifactResult in variantArtifactResults) {
//            println(variantArtifactResult.variantDescriptor.toString() + " " + variantArtifactResult.file)
//        }
//        println()
        val imageSpecs = imageSpecsProvider.get()
//        val variantDescriptorToInput = artifactResults.groupBy({ it.variant.toDescriptor() }) { it.file }
//            .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
        val variantDescriptorToInput = variantArtifactResults.groupBy({ it.variantDescriptor }) { it.file }
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
     */
    return artifacts.mapMetadata { variantArtifactResults ->
        val imageSpecs = imageSpecsProvider.get()
        val variantDescriptorToInput = variantArtifactResults.groupBy({ it.variantDescriptor }) { it.file }
            .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
        imageSpecs.map { imageSpec ->
            OciImagesTask.ImageInput(
                imageSpec.platform,
                imageSpec.variants.mapNotNull { variant -> variantDescriptorToInput[variant.toDescriptor()] },
                imageSpec.referenceSpecs,
            )
        }
    }
}

private data class VariantArtifactResult(val variantDescriptor: VariantDescriptor, val file: File)

private data class VariantDescriptor(
    val owner: ComponentIdentifier,
    val capabilities: List<Capability>,
    val attributes: Map<String, String>,
)

private fun ResolvedVariantResult.toDescriptor() = VariantDescriptor(owner, capabilities, attributes.toMap())

private fun AttributeContainer.toMap(): Map<String, String> =
    keySet().associateBy({ it.name }) { getAttribute(it).toString() }

private fun <R : Any> ArtifactCollection.mapMetadata(transformer: (Set<VariantArtifactResult>) -> R): Provider<R> {
    val artifactResultsProvider = resolvedArtifacts
    return artifactResultsProvider.flatMap { _ ->
        val variantArtifactResults = LinkedHashSet<VariantArtifactResult>()
        (this as ArtifactCollectionInternal).visitArtifacts(object : ArtifactVisitor {
            override fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: ImmutableCapabilities,
                artifact: ResolvableArtifact,
            ) {
                @Suppress("UNCHECKED_CAST") val capabilitySet =
                    ImmutableCapabilities::class.java.getMethod("asSet").invoke(capabilities) as Set<Capability>
                visitArtifact(variantName, attributes, capabilitySet.toList(), artifact)
            }

            @Suppress("UNUSED_PARAMETER")
            fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: List<Capability>,
                artifact: ResolvableArtifact,
            ) {
                variantArtifactResults += VariantArtifactResult(
                    VariantDescriptor(artifact.id.componentIdentifier, capabilities, attributes.toMap()),
                    artifact.file,
                )
            }

            override fun visitFailure(failure: Throwable) = Unit

            override fun requireArtifactFiles() = true
        })
        val transformed = transformer(variantArtifactResults)
        artifactResultsProvider.map { transformed }
    }
}
