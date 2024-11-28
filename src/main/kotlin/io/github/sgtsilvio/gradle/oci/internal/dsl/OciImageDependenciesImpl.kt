package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toVariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.variantArtifacts
import io.github.sgtsilvio.gradle.oci.internal.resolution.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependenciesImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    private val configurationContainer: ConfigurationContainer,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDependencies {

    final override fun getName() = name

    final override val runtime = objectFactory.newInstance<ReferencableOciImageDependencyCollectorImpl>()

    private val indexConfiguration: Configuration = configurationContainer.create(name + "OciImages").apply {
        description = "OCI image dependencies '${this@OciImageDependenciesImpl.name}'"
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

    private inline fun getOrCreatePlatformConfiguration(
        platform: Platform,
        platformSelectorString: String?,
        init: Configuration.() -> Unit,
    ): Configuration {
        val name = name
        var platformConfigurationName = "${name}OciImages@$platform"
        if (platformSelectorString != null) {
            platformConfigurationName += "($platformSelectorString)"
        }
        return platformConfigurations.getOrPut(platformConfigurationName) {
            configurationContainer.create(platformConfigurationName).apply {
                description = buildString {
                    append("OCI image dependencies '").append(name).append("' for platform ").append(platform)
                    if (platformSelectorString != null) {
                        append(" selected by ").append(platformSelectorString)
                    }
                    append('.')
                }
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes.apply {
                    attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                    attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                    attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                    attribute(PLATFORM_ATTRIBUTE, platform.toString())
                }
                init()
            }
        }
    }

    private val indexGraph = lazy { resolveOciVariantGraph(indexConfiguration.incoming) }
    private val allDependencies = lazy { indexConfiguration.allDependencies.filterIsInstance<ModuleDependency>() }

    final override fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>> {
        val lazy = lazy {
            val platformSelector = platformSelectorProvider.orNull
            val singlePlatform = platformSelector?.singlePlatformOrNull()
            val selectedPlatformsGraph: OciVariantGraphWithSelectedPlatforms?
            val platformConfigurationPairs: List<Pair<Platform, Configuration>>
            if (singlePlatform == null) {
                val allDependencies = allDependencies.value
                selectedPlatformsGraph = indexGraph.value.selectPlatforms(platformSelector)
                platformConfigurationPairs = selectedPlatformsGraph.groupByPlatform().map { (platform, graph) ->
                    val platformSelectorString = platformSelector?.toString() ?: "all supported"
                    val platformConfiguration = getOrCreatePlatformConfiguration(platform, platformSelectorString) {
                        val variantSelectors = graph.flatMapTo(HashSet()) { it.variantSelectors }
                        dependencies.addAll(allDependencies.filter { it.toVariantSelector() in variantSelectors })
                        shouldResolveConsistentlyWith(indexConfiguration)
                    }
                    Pair(platform, platformConfiguration)
                }
            } else {
                selectedPlatformsGraph = null
                platformConfigurationPairs = listOf(
                    Pair(singlePlatform, getOrCreatePlatformConfiguration(singlePlatform, null) {
                        dependencies.addAll(indexConfiguration.allDependencies)
                        dependencyConstraints.addAll(indexConfiguration.allDependencyConstraints)
                    })
                )
            }
            val taskDependenciesProvider = objectFactory.listProperty<Any>()
            val imageInputs = ArrayList<OciImagesTask.ImageInput>()
            val variantSelectorsToImageInput = HashMap<Pair<Platform, Set<VariantSelector>>, OciImagesTask.ImageInput>()
            for ((platform, configuration) in platformConfigurationPairs) {
                val artifacts = configuration.incoming.artifacts
                taskDependenciesProvider.addAll(artifacts.resolvedArtifacts)
                val capabilitiesToVariantInput = artifacts.variantArtifacts.groupBy({ it.capabilities }) { it.file }
                    .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
                val imageSpecs = collectOciImageSpecs(configuration.incoming.resolutionResult.root)
                for (imageSpec in imageSpecs) {
                    val imageInput = OciImagesTask.ImageInput(
                        platform,
                        imageSpec.variants.map { variant ->
                            capabilitiesToVariantInput[variant.capabilities] ?: throw IllegalStateException() // TODO message
                        },
                        imageSpec.selectors.collectReferenceSpecs(),
                    )
                    if (selectedPlatformsGraph == null) {
                        imageInputs += imageInput
                    } else {
                        variantSelectorsToImageInput[Pair(platform, imageSpec.selectors)] = imageInput
                    }
                }
            }
            if (selectedPlatformsGraph != null) {
                for ((graphRoot, platforms) in selectedPlatformsGraph) {
                    for (platform in platforms) {
                        imageInputs += variantSelectorsToImageInput[Pair(platform, graphRoot.variantSelectors)]
                            ?: throw IllegalStateException() // TODO message
                    }
                }
            }
            taskDependenciesProvider.map { imageInputs }
        }
        return providerFactory.provider { lazy.value }.flatMap { it }
    }
}
