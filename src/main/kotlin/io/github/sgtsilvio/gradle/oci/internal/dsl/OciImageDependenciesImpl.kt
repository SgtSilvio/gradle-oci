package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.internal.gradle.VariantSelector
import io.github.sgtsilvio.gradle.oci.internal.gradle.toId
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
    private val configurationContainer: ConfigurationContainer,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDependencies {

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

    private val allDependencies = lazy { indexConfiguration.allDependencies.filterIsInstance<ModuleDependency>() }

    final override fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>> {
//        val lazy = lazy {
//            val graph = resolveOciVariantGraph(indexConfiguration.incoming.resolutionResult.root)
        return indexConfiguration.incoming.resolutionResult.rootComponent.flatMap { rootComponent ->
            val graph = try {
                resolveOciVariantGraph(rootComponent)
            } catch (e: ResolutionException) {
                indexConfiguration.incoming.artifacts.failures // throws the failures
                throw e
            }
            val platformSelector = platformSelectorProvider.orNull
            val graphRootAndPlatformsList = selectPlatforms(graph, platformSelector)
            val platformToGraphRoots = HashMap<Platform, ArrayList<OciVariantGraphRoot>>()
            for ((graphRoot, platforms) in graphRootAndPlatformsList) {
                for (platform in platforms) {
                    platformToGraphRoots.getOrPut(platform) { ArrayList() } += graphRoot
                }
            }
            val allDependencies = allDependencies.value
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
                        val variantSelectors = graphRoots.flatMapTo(HashSet()) { it.variantSelectors }
                        dependencies.addAll(allDependencies.filter { it.toVariantSelector() in variantSelectors })
                    }
                }
            }
            val taskDependenciesProvider = objectFactory.listProperty<Any>()
            val variantSelectorsToImageInput = HashMap<Pair<Platform, Set<VariantSelector>>, OciImagesTask.ImageInput>()
            for ((platform, configuration) in platformToConfiguration) {
                val artifacts = configuration.incoming.artifacts
                taskDependenciesProvider.addAll(artifacts.resolvedArtifacts)
                val variantDescriptorToInput = artifacts.variantArtifacts.groupBy({ it.variantId }) { it.file }
                    .mapValues { (_, files) -> OciImagesTask.VariantInput(files.first(), files.drop(1)) }
                val imageSpecs = collectOciImageSpecs(configuration.incoming.resolutionResult.root)
                for (imageSpec in imageSpecs) {
                    val imageInput = OciImagesTask.ImageInput(
                        platform,
                        imageSpec.variants.map { variant ->
                            variantDescriptorToInput[variant.toId()] ?: throw IllegalStateException() // TODO message
                        },
                        imageSpec.selectors.collectReferenceSpecs(),
                    )
                    variantSelectorsToImageInput[Pair(platform, imageSpec.selectors)] = imageInput
                }
            }
            val imageInputs = ArrayList<OciImagesTask.ImageInput>()
            for ((graphRoot, platforms) in graphRootAndPlatformsList) {
                for (platform in platforms) {
                    imageInputs += variantSelectorsToImageInput[Pair(platform, graphRoot.variantSelectors)]
                        ?: throw IllegalStateException() // TODO message
                }
            }
            taskDependenciesProvider.map { imageInputs }
        }
//        return providerFactory.provider { lazy.value }.flatMap { it }
    }
}
