package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Nameable
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Taggable
import io.github.sgtsilvio.gradle.oci.internal.gradle.attribute
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
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
    configurationContainer.create(name + "OciImages").apply {
        description = "OCI image dependencies '$name'"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.apply {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            attribute(PLATFORM_ATTRIBUTE, MULTI_PLATFORM_ATTRIBUTE_VALUE)
        }
    },
    dependencyHandler,
), ResolvableOciImageDependencies {

    final override fun getName() = name

    final override fun DependencySet.addInternal(dependency: ModuleDependency): ReferenceSpecsBuilder {
        val referenceSpecsBuilder = ReferenceSpecsBuilder(objectFactory)
        addLater(referenceSpecsBuilder.attribute.map { attribute ->
            dependency.attribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE, attribute)
        })
        return referenceSpecsBuilder
    }

    final override fun DependencySet.addInternal(dependencyProvider: Provider<out ModuleDependency>): ReferenceSpecsBuilder {
        val referenceSpecsBuilder = ReferenceSpecsBuilder(objectFactory)
        addLater(dependencyProvider.zip(referenceSpecsBuilder.attribute) { dependency, attribute ->
            dependency.attribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE, attribute)
        })
        return referenceSpecsBuilder
    }

    class ReferenceSpecsBuilder(objectFactory: ObjectFactory) : Nameable, Taggable {
        private val nameProperty = objectFactory.property<String>()
        private val tagsProperty = objectFactory.setProperty<String>()
        val attribute: Provider<String> = tagsProperty.zipAbsentAsNull(nameProperty) { tags, name ->
            if (tags.isEmpty()) {
                OciImageReferenceSpec(name, null).toString()
            } else {
                tags.map { tag -> OciImageReferenceSpec(name, if (tag == ".") null else tag) }.joinToString(",")
            }
        }

        override fun name(name: String): ReferenceSpecsBuilder {
            nameProperty.set(name)
            return this
        }

        override fun name(nameProvider: Provider<String>): ReferenceSpecsBuilder {
            nameProperty.set(nameProvider)
            return this
        }

        override fun tag(vararg tags: String): ReferenceSpecsBuilder {
            tagsProperty.addAll(*tags)
            return this
        }

        override fun tag(tags: Iterable<String>): ReferenceSpecsBuilder {
            tagsProperty.addAll(tags)
            return this
        }

        override fun tag(tagProvider: Provider<String>): ReferenceSpecsBuilder {
            tagsProperty.addAll(tagProvider.map { listOf(it) }.orElse(emptyList()))
            return this
        }

        @Suppress("INAPPLICABLE_JVM_NAME")
        @JvmName("tagMultiple")
        override fun tag(tagsProvider: Provider<out Iterable<String>>): ReferenceSpecsBuilder {
            tagsProperty.addAll(tagsProvider.map { it }.orElse(emptyList()))
            return this
        }
    }
}
