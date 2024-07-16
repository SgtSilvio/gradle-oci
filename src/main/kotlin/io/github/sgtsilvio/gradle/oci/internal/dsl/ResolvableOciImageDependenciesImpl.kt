package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Nameable
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.Taggable
import io.github.sgtsilvio.gradle.oci.internal.gradle.attribute
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import org.gradle.api.artifacts.ConfigurationContainer
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

    final override fun addInternal(dependency: ModuleDependency): ReferenceSpecBuilder {
        val referenceSpecBuilder = ReferenceSpecBuilder(objectFactory)
        configuration.dependencies.addLater(referenceSpecBuilder.attribute.map { attribute ->
            dependency.attribute(OCI_IMAGE_REFERENCE_ATTRIBUTE, attribute)
        })
        return referenceSpecBuilder
    }

    final override fun addInternal(dependencyProvider: Provider<out ModuleDependency>): ReferenceSpecBuilder {
        val referenceSpecBuilder = ReferenceSpecBuilder(objectFactory)
        configuration.dependencies.addLater(dependencyProvider.zip(referenceSpecBuilder.attribute) { dependency, attribute ->
            dependency.attribute(OCI_IMAGE_REFERENCE_ATTRIBUTE, attribute)
        })
        return referenceSpecBuilder
    }

    class ReferenceSpecBuilder(objectFactory: ObjectFactory) : Nameable, Taggable {
        private val nameProperty = objectFactory.property<String>()
        private val tagsProperty = objectFactory.setProperty<String>()
        val attribute: Provider<String> = tagsProperty.zipAbsentAsNull(nameProperty) { tags, name ->
            if (tags.isEmpty()) {
                OciImageReferenceSpec(name, null).toString()
            } else {
                tags.map { tag -> OciImageReferenceSpec(name, if (tag == ".") null else tag) }.joinToString(",")
            }
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
