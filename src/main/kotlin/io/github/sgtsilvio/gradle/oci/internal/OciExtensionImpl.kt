package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciExtension
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.capabilities.Capability
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import javax.inject.Inject
import kotlin.collections.set

/**
 * @author Silvio Giebl
 */
abstract class OciExtensionImpl @Inject constructor(objectFactory: ObjectFactory) : OciExtension {

    override val imageDefinitions = objectFactory.domainObjectContainer(OciExtension.ImageDefinition::class) { name ->
        objectFactory.newInstance<ImageDefinition>(name)
    }

    override fun platform(
        os: String,
        architecture: String,
        variant: String?,
        osVersion: String?,
        osFeatures: List<String>,
    ) = Platform(os, architecture, variant, osVersion, osFeatures)

    data class Platform(
        override val os: String,
        override val architecture: String,
        override val variant: String?,
        override val osVersion: String?,
        override val osFeatures: List<String>,
    ) : OciExtension.Platform

    abstract class ImageDefinition @Inject constructor(
        private val name: String,
        private val objectFactory: ObjectFactory,
        configurationContainer: ConfigurationContainer,
    ) : OciExtension.ImageDefinition {
        private val imageConfiguration = createConfiguration(configurationContainer, name, objectFactory)
        override val capabilities: Set<Capability> get() = imageConfiguration.outgoing.capabilities.toSet()
//        override val component: Provider<OciComponent> = providerFactory.provider { createComponent }
        private val bundles = objectFactory.domainObjectSet(Bundle::class)
        private var platformBundles: MutableMap<OciExtension.Platform, Bundle>? = null

        override fun getName() = name

        override fun capabilities(configuration: Action<in OciExtension.ImageDefinition.Capabilities>) =
            configuration.execute(objectFactory.newInstance<Capabilities>(imageConfiguration))

        override fun allPlatforms(configuration: Action<in OciExtension.ImageDefinition.Bundle>) =
            bundles.configureEach(configuration)

        override fun addPlatform(platform: OciExtension.Platform) {
            addPlatformInternal(platform)
        }

        override fun addPlatform(
            platform: OciExtension.Platform,
            configuration: Action<in OciExtension.ImageDefinition.Bundle>,
        ) = configuration.execute(addPlatformInternal(platform))

        override fun platformsMatching(
            spec: Spec<in OciExtension.Platform>,
            configuration: Action<in OciExtension.ImageDefinition.Bundle>,
        ) = bundles.matching { bundle -> if (bundle is PlatformBundle) spec.isSatisfiedBy(bundle.platform) else false }
            .configureEach(configuration)

        private fun addPlatformInternal(platform: OciExtension.Platform): Bundle {
            var platformBundles = platformBundles
            if (platformBundles == null) {
                if (!bundles.isEmpty()) {
                    throw IllegalStateException("adding platform $platform is not possible because multi-platform is already declared")
                }
                platformBundles = mutableMapOf()
                this.platformBundles = platformBundles
            } else if (platform in platformBundles) {
                throw IllegalStateException("adding platform $platform is not possible because it is already present")
            }
            val bundle = objectFactory.newInstance<PlatformBundle>(name, imageConfiguration, platform)
            bundles.add(bundle)
            platformBundles[platform] = bundle
            return bundle
        }

        private fun getBundleOrPlatformBundles(): BundleOrPlatformBundles {
            val platformBundles = platformBundles ?: return when (bundles.size) {
                0 -> objectFactory.newInstance<Bundle>(name, imageConfiguration).also { bundles.add(it) }
                1 -> bundles.first()
                else -> throw IllegalStateException("bug: multiple bundles without platform are not possible")
            }
            return PlatformBundles(platformBundles)
        }

        private fun createConfiguration(
            configurationContainer: ConfigurationContainer,
            imageName: String,
            objectFactory: ObjectFactory,
        ) = configurationContainer.create(createConfigurationName(imageName)) {
            description = "OCI elements for $imageName"
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named("distribution"))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named("oci-image"))
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
//                attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
            }
        }

        private fun createConfigurationName(imageName: String) =
            if (imageName == "main") "ociImage" else "${imageName}OciImage"

        abstract class Capabilities @Inject constructor(
            private val imageConfiguration: Configuration,
        ) : OciExtension.ImageDefinition.Capabilities {

            override fun capability(group: String, name: String) =
                imageConfiguration.outgoing.capability("$group:$name:default")
        }

        sealed interface BundleOrPlatformBundles

        abstract class Bundle @Inject constructor(
            private val imageName: String,
            private val imageConfiguration: Configuration,
            private val objectFactory: ObjectFactory,
        ) : OciExtension.ImageDefinition.Bundle, BundleOrPlatformBundles {

            override val parentImages = objectFactory.newInstance<ParentImages>(imageConfiguration)

            init {
                entryPoint.convention(null)
                arguments.convention(null)
            }

            override fun parentImages(configuration: Action<in OciExtension.ImageDefinition.Bundle.ParentImages>) =
                configuration.execute(parentImages)

            override fun layers(configuration: Action<in OciExtension.ImageDefinition.Bundle.Layers>) =
                configuration.execute(objectFactory.newInstance<Layers>(imageName, imageConfiguration, layers))

            abstract class ParentImages @Inject constructor(
                private val imageConfiguration: Configuration,
                private val dependencyHandler: DependencyHandler,
            ) : OciExtension.ImageDefinition.Bundle.ParentImages {

                override fun add(dependency: ModuleDependency) {
                    val finalizedDependency = finalizeDependency(dependency)
                    dependencies.add(finalizedDependency)
                    imageConfiguration.dependencies.add(finalizedDependency)
                }

                override fun <D : ModuleDependency> add(dependency: D, configuration: Action<in D>) {
                    val finalizedDependency = finalizeDependency(dependency)
                    configuration.execute(finalizedDependency)
                    dependencies.add(finalizedDependency)
                    imageConfiguration.dependencies.add(finalizedDependency)
                }

                override fun add(dependencyProvider: Provider<out ModuleDependency>) {
                    val finalizedDependencyProvider = dependencyProvider.map { finalizeDependency(it) }
                    dependencies.addLater(finalizedDependencyProvider)
                    imageConfiguration.dependencies.addLater(finalizedDependencyProvider)
                }

                override fun <D : ModuleDependency> add(
                    dependencyProvider: Provider<out D>,
                    configuration: Action<in D>,
                ) {
                    val finalizedDependencyProvider = dependencyProvider.map {
                        val finalizedDependency = finalizeDependency(it)
                        configuration.execute(finalizedDependency)
                        finalizedDependency
                    }
                    dependencies.addLater(finalizedDependencyProvider)
                    imageConfiguration.dependencies.addLater(finalizedDependencyProvider)
                }

                @Suppress("UNCHECKED_CAST")
                private fun <D : ModuleDependency> finalizeDependency(dependency: D) =
                    dependencyHandler.create(dependency) as D

                override fun module(dependencyNotation: CharSequence) =
                    dependencyHandler.create(dependencyNotation) as ExternalModuleDependency

                override fun module(dependencyProvider: Provider<out MinimalExternalModuleDependency>) =
                    dependencyProvider.map { dependencyHandler.create(it) as ExternalModuleDependency }

                private fun project(project: Project) = dependencyHandler.create(project) as ProjectDependency

                override fun add(dependencyNotation: CharSequence) = add(module(dependencyNotation))

                override fun add(dependencyNotation: CharSequence, configuration: Action<in ExternalModuleDependency>) =
                    add(module(dependencyNotation), configuration)

                override fun add(project: Project) = add(project(project))

                override fun add(project: Project, configuration: Action<in ProjectDependency>) =
                    add(project(project), configuration)
            }

            abstract class Layers @Inject constructor(
                private val imageName: String,
                private val imageConfiguration: Configuration,
                private val layers: NamedDomainObjectList<OciExtension.ImageDefinition.Bundle.Layer>,
                private val objectFactory: ObjectFactory,
            ) : OciExtension.ImageDefinition.Bundle.Layers {

                override fun layer(name: String, configuration: Action<in OciExtension.ImageDefinition.Bundle.Layer>) {
                    val layer = objectFactory.newInstance<Layer>(name, imageName, imageConfiguration)
                    layers.add(layer)
                    configuration.execute(layer)
                }
            }

            abstract class Layer @Inject constructor(
                private val name: String,
                private val imageName: String,
                private val imageConfiguration: Configuration,
                private val taskContainer: TaskContainer,
                private val projectLayout: ProjectLayout,
            ) : OciExtension.ImageDefinition.Bundle.Layer {

                private var task: TaskProvider<OciLayerTask>? = null
                    set(value) {
                        field = value
                        if (value != null) {
                            imageConfiguration.outgoing.artifact(value)
                        }
                    }

                init {
                    createdBy.convention("gradle-oci: $name")
                }

                override fun getName() = name

                override fun contents(configuration: Action<in OciCopySpec>) {
                    var task = task
                    if (task == null) {
                        task = taskContainer.register<OciLayerTask>(createTaskName(imageName, name)) {
                            outputDirectory.convention(projectLayout.buildDirectory.dir("oci/$imageName/${this@Layer.name}"))
                            contents(configuration)
                        }
                        this.task = task
                    } else {
                        task.configure {
                            contents(configuration)
                        }
                    }
                }

                override fun contents(task: TaskProvider<OciLayerTask>) {
                    if (this.task != null) {
                        throw IllegalStateException("layer task already set ${this.task}, can not replace with $task")
                    }
                    this.task = task
                }

                private fun createTaskName(imageName: String, name: String) =
                    if (imageName == "main") "${name}OciLayer" else "${imageName}${name.capitalize()}OciLayer"
            }
        }

        abstract class PlatformBundle @Inject constructor(
            imageName: String,
            imageConfiguration: Configuration,
            val platform: OciExtension.Platform?,
            objectFactory: ObjectFactory,
        ) : Bundle(imageName, imageConfiguration, objectFactory)

        class PlatformBundles(val map: Map<OciExtension.Platform, Bundle>) : BundleOrPlatformBundles
    }
}