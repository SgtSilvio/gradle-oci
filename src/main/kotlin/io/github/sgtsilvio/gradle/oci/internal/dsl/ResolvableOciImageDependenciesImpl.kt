package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies.*
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedDependencyResult
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

    private val dependencyReferenceSpecsPairs = objectFactory.listProperty<Pair<ModuleDependency, List<ReferenceSpec>>>()

    final override val rootCapabilities: Provider<Map<Coordinates, Set<ReferenceSpec>>> =
        configuration.incoming.resolutionResult.rootComponent.zip(dependencyReferenceSpecsPairs) { rootComponent, dependencyReferenceSpecsPairs ->
            val descriptorToReferenceSpecs = HashMap<ModuleDependencyDescriptor, List<ReferenceSpec>>()
            for ((dependency, referenceSpecs) in dependencyReferenceSpecsPairs) {
                descriptorToReferenceSpecs.merge(dependency.toDescriptor(), referenceSpecs) { a, b -> a + b }
            }
            val coordinatesToReferenceSpecs = HashMap<Coordinates, HashSet<ReferenceSpec>>()
            val rootVariant = rootComponent.variants.first()
            for (dependency in rootComponent.getDependenciesForVariant(rootVariant)) {
                if (dependency.isConstraint) continue
                if (dependency !is ResolvedDependencyResult) continue
                val referenceSpecs = descriptorToReferenceSpecs[dependency.requested.toDescriptor()]
                if (referenceSpecs != null) {
                    val capability = dependency.resolvedVariant.capabilities.first()
                    coordinatesToReferenceSpecs.getOrPut(Coordinates(capability.group, capability.name)) { HashSet() }
                        .addAll(referenceSpecs)
                }
            }
            coordinatesToReferenceSpecs
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
        val referenceSpecs: Provider<List<ReferenceSpec>> = tagsProperty.zipAbsentAsNull(nameProperty) { tags, name ->
            if (tags.isEmpty()) {
                listOf(ReferenceSpec(name, null))
            } else {
                tags.map { tag -> ReferenceSpec(name, if (tag == ".") null else tag) }
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

private interface ModuleDependencyDescriptor

private data class ProjectDependencyDescriptor(
    val projectPath: String,
    val requestedCapabilities: List<Capability>,
    val attributes: AttributeContainer,
) : ModuleDependencyDescriptor

private data class ExternalDependencyDescriptor(
    val group: String,
    val name: String,
    val requestedCapabilities: List<Capability>,
    val attributes: AttributeContainer,
) : ModuleDependencyDescriptor

private fun ModuleDependency.toDescriptor() = when (this) {
    is ProjectDependency -> ProjectDependencyDescriptor(dependencyProject.path, requestedCapabilities, attributes)
    is ExternalDependency -> ExternalDependencyDescriptor(group, name, requestedCapabilities, attributes)
    else -> throw IllegalStateException("expected ProjectDependency or ExternalDependency, got: $this")
}

private fun ComponentSelector.toDescriptor() = when (this) {
    is ProjectComponentSelector -> ProjectDependencyDescriptor(projectPath, requestedCapabilities, attributes)
    is ModuleComponentSelector -> ExternalDependencyDescriptor(group, module, requestedCapabilities, attributes)
    else -> throw IllegalStateException("expected ProjectComponentSelector or ModuleComponentSelector, got: $this")
}
