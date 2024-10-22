package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.internal.resolution.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependenciesImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    private val configurationContainer: ConfigurationContainer,
    private val dependencyHandler: DependencyHandler,
) : DependencyConstraintFactoriesImpl(dependencyHandler.constraints), OciImageDependencies {

    final override fun getName() = name

    final override val runtime = objectFactory.newInstance<ReferencableOciImageDependencyCollectorImpl>()

    private val indexConfiguration: Configuration = configurationContainer.create(name + "OciImages").apply {
        description = "OCI image dependencies '$name'"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.apply {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_INDEX_DISTRIBUTION_TYPE)
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
        }
        dependencies.addAllLater(runtime.dependencies)
        dependencyConstraints.addAllLater(runtime.dependencyConstraints)
    }

    private val platformConfigurations = HashMap<String, Configuration>()

    final override fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>> {
        return indexConfiguration.incoming.resolutionResult.rootComponent.flatMap { rootComponent ->
            val graph = resolveOciVariantGraph(rootComponent)
            val platformSelector = platformSelectorProvider.orNull
            val graphRootAndPlatformsList = selectPlatforms(graph, platformSelector)
            val platformToGraphRoots = HashMap<Platform, ArrayList<OciVariantGraphRoot>>()
            for ((graphRoot, platforms) in graphRootAndPlatformsList) {
                for (platform in platforms) {
                    platformToGraphRoots.getOrPut(platform) { ArrayList() } += graphRoot
                }
            }
            val platformToConfiguration = platformToGraphRoots.mapValues { (platform, graphRoots) ->
                val platformConfigurationName =
                    "${indexConfiguration.name}@$platform" + if (platformSelector == null) "" else "($platformSelector)"
                platformConfigurations.getOrPut(platformConfigurationName) {
                    configurationContainer.create(platformConfigurationName).apply {
                        isCanBeConsumed = false
                        isCanBeResolved = true
                        attributes.apply {
                            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                            attribute(PLATFORM_ATTRIBUTE, platform.toString())
                        }
                        shouldResolveConsistentlyWith(indexConfiguration)
                        for (graphRoot in graphRoots) {
                            for (selector in graphRoot.selectors) {
                                dependencies.add(selector.toDependency(dependencyHandler))
                            }
                        }
                    }
                }
            }
            val taskDependenciesProvider = objectFactory.listProperty<Any>()
            val capabilityAndPlatformToInput = HashMap<Triple<String, String, Platform>, OciImagesTask.VariantInput>()
            for ((platform, configuration) in platformToConfiguration) {
                val artifacts = configuration.incoming.artifacts
                taskDependenciesProvider.addAll(artifacts.resolvedArtifacts)
                val variantDescriptorToInput = artifacts.variantArtifacts.groupBy({ it.variantDescriptor }) { it.file }
                    .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
                for ((variantDescriptor, variantInput) in variantDescriptorToInput) {
                    for (capability in variantDescriptor.capabilities) {
                        capabilityAndPlatformToInput[Triple(capability.group, capability.name, platform)] = variantInput
                    }
                }
            }
            val imageInputs = ArrayList<OciImagesTask.ImageInput>()
            for ((graphRoot, platforms) in graphRootAndPlatformsList) {
                for (platform in platforms) {
                    val variantInputs = graphRoot.collectOciVariants(platform).map { variant ->
                        val anyCapability = variant.capabilities.first()
                        capabilityAndPlatformToInput[Triple(anyCapability.group, anyCapability.name, platform)]
                            ?: throw IllegalStateException() // TODO message
                    }
                    imageInputs += OciImagesTask.ImageInput(platform, variantInputs, graphRoot.referenceSpecs)
                }
            }
            taskDependenciesProvider.map { imageInputs }
        }
    }
}

internal fun ComponentSelector.toDependency(dependencyHandler: DependencyHandler): ModuleDependency {
    val dependency = when (this) {
        is ModuleComponentSelector -> dependencyHandler.create(group, module).apply {
            version {
                branch = versionConstraint.branch
                prefer(versionConstraint.preferredVersion)
                if (versionConstraint.strictVersion == "") {
                    require(versionConstraint.requiredVersion)
                } else {
                    strictly(versionConstraint.strictVersion)
                }
                reject(*versionConstraint.rejectedVersions.toTypedArray())
            }
        }

        is ProjectComponentSelector -> dependencyHandler.project(projectPath)
        else -> throw IllegalStateException("expected Module- or ProjectComponentSelector, but got $this")
    }
    val attributes = attributes
    dependency.attributes {
        for (attribute in attributes.keySet()) {
            @Suppress("UNCHECKED_CAST")
            attribute(attribute as Attribute<Any>, attributes.getAttribute(attribute)!!)
        }
    }
    dependency.capabilities {
        requireCapabilities(*requestedCapabilities.toTypedArray())
    }
    return dependency
}
