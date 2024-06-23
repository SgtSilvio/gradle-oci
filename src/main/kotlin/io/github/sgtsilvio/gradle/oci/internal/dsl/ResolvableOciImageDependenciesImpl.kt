package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.*
import io.github.sgtsilvio.gradle.oci.internal.gradle.optional
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
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

    private val dependencyReferencesPairs = objectFactory.listProperty<Pair<ModuleDependency, List<Reference>>>()

    final override val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>> =
        configuration.incoming.resolutionResult.rootComponent.zip(dependencyReferencesPairs) { rootComponent, dependencyReferencesPairs ->
            val descriptorToReferences = HashMap<ModuleDependencyDescriptor, List<Reference>>()
            for ((dependency, references) in dependencyReferencesPairs) {
                descriptorToReferences.merge(dependency.toDescriptor(), references) { a, b -> a + b }
            }
            val coordinatesToReferences = HashMap<Coordinates, HashSet<Reference>>()
            val rootVariant = rootComponent.variants.first()
            for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
                if (dependency.isConstraint) continue
                if (dependency !is ResolvedDependencyResult) continue
                val references = descriptorToReferences[dependency.requested.toDescriptor()]
                if (references != null) {
                    val capability = dependency.resolvedVariant.capabilities.first()
                    coordinatesToReferences.getOrPut(Coordinates(capability.group, capability.name)) { HashSet() }
                        .addAll(references)
                }
            }
            coordinatesToReferences
        }

    final override fun getName() = name

    final override fun returnType(dependency: ModuleDependency): ReferenceSpec {
        val referenceSpec = ReferenceSpec(objectFactory)
        dependencyReferencesPairs.add(referenceSpec.references.map { Pair(dependency, it) })
        return referenceSpec
    }

    final override fun returnType(dependencyProvider: Provider<out ModuleDependency>): ReferenceSpec {
        val referenceSpec = ReferenceSpec(objectFactory)
        dependencyReferencesPairs.add(dependencyProvider.zip(referenceSpec.references, ::Pair))
        return referenceSpec
    }

    class ReferenceSpec(objectFactory: ObjectFactory) : Nameable, Taggable {
        private val nameProperty = objectFactory.property<String>()
        private val tagsProperty = objectFactory.setProperty<String>()
        val references: Provider<List<Reference>> = nameProperty.optional().zip(tagsProperty) { optionalName, tags ->
            val name = optionalName.orElse(null)
            if (tags.isEmpty()) {
                listOf(Reference(name, null))
            } else {
                tags.map { tag -> Reference(name, if (tag == ".") null else tag) }
            }
        }

        override fun name(name: String): ReferenceSpec {
            nameProperty.set(name)
            return this
        }

        override fun name(nameProvider: Provider<String>): ReferenceSpec {
            nameProperty.set(nameProvider)
            return this
        }

        override fun tag(vararg tags: String): ReferenceSpec {
            tagsProperty.addAll(*tags)
            return this
        }

        override fun tag(tags: Iterable<String>): ReferenceSpec {
            tagsProperty.addAll(tags)
            return this
        }

        override fun tag(tagProvider: Provider<String>): ReferenceSpec {
            tagsProperty.addAll(tagProvider.map { listOf(it) }.orElse(emptyList()))
            return this
        }

        @Suppress("INAPPLICABLE_JVM_NAME")
        @JvmName("tagMultiple")
        override fun tag(tagsProvider: Provider<out Iterable<String>>): ReferenceSpec {
            tagsProperty.addAll(tagsProvider.map { it }.orElse(emptyList()))
            return this
        }
    }
}

private interface ModuleDependencyDescriptor

private data class ProjectDependencyDescriptor(
    val projectPath: String,
    val requestedCapabilities: List<Coordinates>,
) : ModuleDependencyDescriptor

private data class ExternalDependencyDescriptor(
    val coordinates: VersionedCoordinates,
    val requestedCapabilities: List<Coordinates>,
) : ModuleDependencyDescriptor

private fun ModuleDependency.toDescriptor(): ModuleDependencyDescriptor {
    val requestedCapabilities = requestedCapabilities.map { Coordinates(it.group, it.name) }
    return when (this) {
        is ProjectDependency -> ProjectDependencyDescriptor(dependencyProject.path, requestedCapabilities)
        is ExternalDependency -> ExternalDependencyDescriptor(
            VersionedCoordinates(group, name, version ?: ""),
            requestedCapabilities,
        )

        else -> throw IllegalStateException("expected ProjectDependency or ExternalDependency, got: $this")
    }
}

private fun ComponentSelector.toDescriptor(): ModuleDependencyDescriptor {
    val requestedCapabilities = requestedCapabilities.map { Coordinates(it.group, it.name) }
    return when (this) {
        is ProjectComponentSelector -> ProjectDependencyDescriptor(projectPath, requestedCapabilities)
        is ModuleComponentSelector -> ExternalDependencyDescriptor(
            VersionedCoordinates(group, module, version),
            requestedCapabilities,
        )

        else -> throw IllegalStateException("expected ProjectComponentSelector or ModuleComponentSelector, got: $this")
    }
}
