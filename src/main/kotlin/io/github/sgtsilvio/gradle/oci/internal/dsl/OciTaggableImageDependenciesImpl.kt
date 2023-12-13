package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciTaggableImageDependencies.*
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciTaggableImageDependenciesImpl @Inject constructor(
    prefix: String,
    description: String,
    private val objectFactory: ObjectFactory,
    configurationContainer: ConfigurationContainer,
    dependencyHandler: DependencyHandler,
) : OciImageDependenciesImpl(
    configurationContainer.create(prefix + "OciImages") {
        this.description = description
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
        }
    },
    dependencyHandler,
), OciTaggableImageDependencies {

    private val dependencies = objectFactory.listProperty<Pair<ModuleDependency, Reference>>()

    final override val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>> =
        configuration.incoming.resolutionResult.rootComponent.map { rootComponent ->
            val descriptorToReferences = HashMap<ModuleDependencyDescriptor, List<Reference>>()
            for ((dependency, reference) in dependencies.get()) {
                descriptorToReferences.merge(dependency.toDescriptor(), listOf(reference)) { a, b -> a + b }
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

    data class ReferenceImpl(override val name: String?, override val tag: String?) : Reference {
        companion object {
            val DEFAULT = ReferenceImpl(null, null)
        }
    }

    class ReferenceSpecImpl(override val reference: Provider<Reference>) : NamedReferenceSpec {
        override fun tagged(tag: String) = ReferenceSpecImpl(reference.map { ReferenceImpl(it.name, tag) })

        override fun tagged(tagProvider: Provider<String>) =
            ReferenceSpecImpl(reference.zipAbsentAsNull(tagProvider) { reference, tag ->
                ReferenceImpl(reference.name, tag)
            })
    }

    // add dependency

    final override fun add(dependency: ModuleDependency) {
        val finalizedDependency = configuration.addDependency(dependency)
        dependencies.add(Pair(finalizedDependency, ReferenceImpl.DEFAULT))
    }

    final override fun <D : ModuleDependency> add(dependency: D, action: Action<in D>) {
        val finalizedDependency = configuration.addDependency(dependency, action)
        dependencies.add(Pair(finalizedDependency, ReferenceImpl.DEFAULT))
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider)
        dependencies.add(finalizedDependencyProvider.map { Pair(it, ReferenceImpl.DEFAULT) })
    }

    final override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, action: Action<in D>) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider, action)
        dependencies.add(finalizedDependencyProvider.map { Pair(it, ReferenceImpl.DEFAULT) })
    }

    // add tagged dependency

    final override fun add(dependency: ModuleDependency, referenceSpec: ReferenceSpec) {
        val finalizedDependency = configuration.addDependency(dependency)
        dependencies.add(referenceSpec.reference.map { Pair(finalizedDependency, it) })
    }

    final override fun <D : ModuleDependency> add(dependency: D, referenceSpec: ReferenceSpec, action: Action<in D>) {
        val finalizedDependency = configuration.addDependency(dependency, action)
        dependencies.add(referenceSpec.reference.map { Pair(finalizedDependency, it) })
    }

    final override fun add(dependencyProvider: Provider<out ModuleDependency>, referenceSpec: ReferenceSpec) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider)
        dependencies.add(finalizedDependencyProvider.zip(referenceSpec.reference, ::Pair))
    }

    final override fun <D : ModuleDependency> add(
        dependencyProvider: Provider<out D>,
        referenceSpec: ReferenceSpec,
        action: Action<in D>,
    ) {
        val finalizedDependencyProvider = configuration.addDependency(dependencyProvider, action)
        dependencies.add(finalizedDependencyProvider.zip(referenceSpec.reference, ::Pair))
    }

    // add tagged dependency converted from a different notation

    final override fun add(dependencyNotation: CharSequence, referenceSpec: ReferenceSpec) =
        add(createDependency(dependencyNotation), referenceSpec)

    final override fun add(
        dependencyNotation: CharSequence,
        referenceSpec: ReferenceSpec,
        action: Action<in ExternalModuleDependency>,
    ) = add(createDependency(dependencyNotation), referenceSpec, action)

    final override fun add(project: Project, referenceSpec: ReferenceSpec) =
        add(createDependency(project), referenceSpec)

    final override fun add(project: Project, referenceSpec: ReferenceSpec, action: Action<in ProjectDependency>) =
        add(createDependency(project), referenceSpec, action)

    final override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        referenceSpec: ReferenceSpec,
    ) = add(dependencyProvider.asProvider(), referenceSpec)

    final override fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        referenceSpec: ReferenceSpec,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider.asProvider(), referenceSpec, action)
}

interface ModuleDependencyDescriptor

data class ProjectDependencyDescriptor(
    val projectPath: String,
    val requestedCapabilities: List<Coordinates>,
) : ModuleDependencyDescriptor

data class ExternalDependencyDescriptor(
    val coordinates: VersionedCoordinates,
    val requestedCapabilities: List<Coordinates>,
) : ModuleDependencyDescriptor

fun ModuleDependency.toDescriptor(): ModuleDependencyDescriptor {
    val requestedCapabilities = requestedCapabilities.map { Coordinates(it.group, it.name) }
    return when (this) {
        is ProjectDependency -> ProjectDependencyDescriptor(dependencyProject.path, requestedCapabilities)
        is ExternalDependency -> ExternalDependencyDescriptor(
            VersionedCoordinates(group ?: "", name, version ?: ""),
            requestedCapabilities,
        )

        else -> throw IllegalStateException() // TODO message
    }
}

fun ComponentSelector.toDescriptor(): ModuleDependencyDescriptor {
    val requestedCapabilities = requestedCapabilities.map { Coordinates(it.group, it.name) }
    return when (this) {
        is ProjectComponentSelector -> ProjectDependencyDescriptor(projectPath, requestedCapabilities)
        is ModuleComponentSelector -> ExternalDependencyDescriptor(
            VersionedCoordinates(group, module, version),
            requestedCapabilities,
        )

        else -> throw IllegalStateException() // TODO message
    }
}
