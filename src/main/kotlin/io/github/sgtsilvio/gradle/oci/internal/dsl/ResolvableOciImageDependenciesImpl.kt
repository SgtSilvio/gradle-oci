package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImageInput
import io.github.sgtsilvio.gradle.oci.OciImageReferenceSpec
import io.github.sgtsilvio.gradle.oci.OciImagesInput
import io.github.sgtsilvio.gradle.oci.OciVariantInput
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.component.ArtifactViewComponentFilter
import io.github.sgtsilvio.gradle.oci.component.resolveOciVariantImages
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Nameable
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Taggable
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.capabilities.Capability
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class ResolvableOciImageDependenciesImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    configurationContainer: ConfigurationContainer,
    dependencyHandler: DependencyHandler,
) : OciImageDependenciesImpl<Nameable>(
    configurationContainer.create(name + "OciImages") {
        description = "OCI image dependencies '$name'"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            attribute(PLATFORM_ATTRIBUTE, MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE)
        }
    },
    dependencyHandler,
), ResolvableOciImageDependencies {

    private val dependencyReferenceSpecsPairs =
        objectFactory.listProperty<Pair<ModuleDependency, List<OciImageReferenceSpec>>>()

    final override fun asInput(): Provider<OciImagesInput> {
        val rootComponentProvider = configuration.incoming.resolutionResult.rootComponent
        val variantImagesProvider = rootComponentProvider.zip(dependencyReferenceSpecsPairs, ::resolveOciVariantImages)
        val artifactsResultsProvider = configuration.incoming.artifactView {
            componentFilter(ArtifactViewComponentFilter(rootComponentProvider, variantImagesProvider))
        }.artifacts.resolvedArtifacts
        // zip or map is not used here because their mapper function is executed after the file contents are available
        //  this mapper function does not read the file contents, so can already be called once the value is available
        //  this allows this mapper function to be run before storing the configuration cache
        //  apart from performance benefits this also avoids a bug where the artifactsResultsProvider value is different when using the configuration cache
        return artifactsResultsProvider.flatMap { artifactsResults ->
            val variantImages = variantImagesProvider.get()
            val variantDescriptorToArtifacts = artifactsResults.groupBy({ it.variant.toDescriptor() }) { it.file }
            val variantInputs = ArrayList<OciVariantInput>(variantDescriptorToArtifacts.size)
            val variantDescriptorToIndex = HashMap<VariantDescriptor, Int>()
            var variantIndex = 0
            for ((variantDescriptor, artifacts) in variantDescriptorToArtifacts) {
                variantInputs += OciVariantInput(artifacts.first(), artifacts.drop(1))
                variantDescriptorToIndex[variantDescriptor] = variantIndex++
            }
            val imageInputs = variantImages.map { variantImage ->
                OciImageInput(
                    variantImage.platform,
                    variantImage.variants.mapNotNull { variant -> variantDescriptorToIndex[variant.toDescriptor()] },
                    variantImage.referenceSpecs,
                )
            }
            val imagesInputs = OciImagesInput(variantInputs, imageInputs)
            // using map to attach the task dependencies from the artifactsResultsProvider
            artifactsResultsProvider.map { imagesInputs }
        }
    }

    final override fun getName() = name

    final override fun returnType(dependency: ModuleDependency): ReferenceSpecBuilder {
        val referenceSpecBuilder = ReferenceSpecBuilder(objectFactory)
        dependencyReferenceSpecsPairs.add(referenceSpecBuilder.referenceSpecs.map { Pair(dependency, it) })
        return referenceSpecBuilder
    }

    final override fun returnType(dependencyProvider: Provider<out ModuleDependency>): ReferenceSpecBuilder {
        val referenceSpecBuilder = ReferenceSpecBuilder(objectFactory)
        dependencyReferenceSpecsPairs.add(dependencyProvider.zip(referenceSpecBuilder.referenceSpecs, ::Pair))
        return referenceSpecBuilder
    }

    class ReferenceSpecBuilder(objectFactory: ObjectFactory) : Nameable, Taggable {
        private val nameProperty = objectFactory.property<String>()
        private val tagsProperty = objectFactory.setProperty<String>()
        val referenceSpecs: Provider<List<OciImageReferenceSpec>> = tagsProperty.zipAbsentAsNull(nameProperty) { tags, name ->
            if (tags.isEmpty()) {
                listOf(OciImageReferenceSpec(name, null))
            } else {
                tags.map { tag -> OciImageReferenceSpec(name, if (tag == ".") null else tag) }
            }
            // TODO if both null/empty -> emptyList?
        }

        override fun name(name: String): ReferenceSpecBuilder {
            nameProperty.set(name)
            return this
        }

        override fun name(nameProvider: Provider<String>): ReferenceSpecBuilder {
            nameProperty.set(nameProvider)
            return this
        }

        override fun tag(vararg tags: String): ReferenceSpecBuilder {
            tagsProperty.addAll(*tags)
            return this
        }

        override fun tag(tags: Iterable<String>): ReferenceSpecBuilder {
            tagsProperty.addAll(tags)
            return this
        }

        override fun tag(tagProvider: Provider<String>): ReferenceSpecBuilder {
            tagsProperty.addAll(tagProvider.map { listOf(it) }.orElse(emptyList()))
            return this
        }

        @Suppress("INAPPLICABLE_JVM_NAME")
        @JvmName("tagMultiple")
        override fun tag(tagsProvider: Provider<out Iterable<String>>): ReferenceSpecBuilder {
            tagsProperty.addAll(tagsProvider.map { it }.orElse(emptyList()))
            return this
        }
    }
}

data class VariantDescriptor( // TODO private
    val owner: ComponentIdentifier,
    val capabilities: List<Capability>,
    val attributes: Map<String, String>,
)

fun ResolvedVariantResult.toDescriptor() = VariantDescriptor(owner, capabilities, attributes.toMap()) // TODO private

private fun AttributeContainer.toMap(): Map<String, String> =
    keySet().associateBy({ it.name }) { getAttribute(it).toString() }
