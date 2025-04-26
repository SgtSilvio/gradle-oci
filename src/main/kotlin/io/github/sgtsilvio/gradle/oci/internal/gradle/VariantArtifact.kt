package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.artifacts.configurations.ArtifactCollectionInternal
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactVisitor
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.ImmutableCapabilities
import java.io.File

internal data class VariantArtifact(
    val componentId: ComponentIdentifier,
    val capabilities: List<Capability>,
    val attributes: Map<String, String>,
    val file: File,
)

internal val ArtifactCollection.variantArtifacts: Set<VariantArtifact>
    get() {
        val variantArtifacts = LinkedHashSet<VariantArtifact>()
        var hasFailure = false
        // we need to use internal APIs to workaround the issue https://github.com/gradle/gradle/issues/29977
        //  ArtifactCollection.getResolvedArtifacts() wrongly deduplicates ResolvedArtifactResults of different variants for the same file
        (this as ArtifactCollectionInternal).visitArtifacts(object : ArtifactVisitor {
            override fun visitArtifact(
                variantName: DisplayName,
                attributes: ImmutableAttributes,
                capabilities: ImmutableCapabilities,
                artifact: ResolvableArtifact,
            ) = visitArtifact(variantName, attributes as AttributeContainer, capabilities, artifact)

            // this method implements the signature of the ArtifactVisitor interface of Gradle versions between 8.6 and 8.13
            fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: ImmutableCapabilities,
                artifact: ResolvableArtifact,
            ) {
                // capabilities.asSet() is defined to return org.gradle.internal.impldep.com.google.common.collect.ImmutableSet during compile time
                //  during runtime it actually returns com.google.common.collect.ImmutableSet
                //  this leads to a NoSuchMethodError during runtime, so we need to use reflection
                //  this allows us to remove the dependency on the specific set implementation
                @Suppress("UNCHECKED_CAST") val capabilitySet =
                    ImmutableCapabilities::class.java.getMethod("asSet").invoke(capabilities) as Set<Capability>
                visitArtifact(variantName, attributes, capabilitySet.toList(), artifact)
            }

            // this method implements the signature of the ArtifactVisitor interface of Gradle versions between 7.2 and 8.5
            @Suppress("UNUSED_PARAMETER")
            fun visitArtifact(
                variantName: DisplayName,
                attributes: AttributeContainer,
                capabilities: List<Capability>,
                artifact: ResolvableArtifact,
            ) {
                variantArtifacts += VariantArtifact(
                    artifact.id.componentIdentifier,
                    capabilities,
                    attributes.toStringMap(),
                    artifact.file,
                )
            }

            override fun visitFailure(failure: Throwable) {
                hasFailure = true
            }

            override fun requireArtifactFiles() = true
        })
        if (hasFailure && !isLenient) {
            failures // throws the failures
        }
        return variantArtifacts
    }
