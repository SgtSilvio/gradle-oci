package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.dsl.ReferencableOciImageDependencyCollector
import io.github.sgtsilvio.gradle.oci.dsl.ReferencableOciImageDependencyCollector.Nameable
import io.github.sgtsilvio.gradle.oci.dsl.ReferencableOciImageDependencyCollector.Taggable
import io.github.sgtsilvio.gradle.oci.internal.gradle.attribute
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReferenceSpec
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class ReferencableOciImageDependencyCollectorImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    dependencyHandler: DependencyHandler,
) : OciImageDependencyCollectorImpl<Nameable>(
    dependencyHandler,
    objectFactory,
), ReferencableOciImageDependencyCollector {

    final override fun addInternal(dependency: ModuleDependency): ReferenceSpecsBuilder {
        val referenceSpecsBuilder = ReferenceSpecsBuilder(objectFactory)
        dependencies.add(referenceSpecsBuilder.attribute.map { attribute ->
            dependency.attribute(OCI_IMAGE_REFERENCE_SPECS_ATTRIBUTE, attribute)
        })
        return referenceSpecsBuilder
    }

    final override fun addInternal(dependencyProvider: Provider<out ModuleDependency>): ReferenceSpecsBuilder {
        val referenceSpecsBuilder = ReferenceSpecsBuilder(objectFactory)
        dependencies.add(dependencyProvider.zip(referenceSpecsBuilder.attribute) { dependency, attribute ->
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
