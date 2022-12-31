package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciComponentTask
import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciExtension
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.capabilities.Capability
import org.gradle.api.file.ProjectLayout
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
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

    final override val imageDefinitions = objectFactory.domainObjectContainer(OciExtension.ImageDefinition::class) { name ->
        objectFactory.newInstance<ImageDefinition>(name)
    }

    init {
        // eagerly realize imageDefinitions because it registers configurations and tasks
        imageDefinitions.all {}
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
        providerFactory: ProviderFactory,
        configurationContainer: ConfigurationContainer,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
    ) : OciExtension.ImageDefinition {

        private val imageConfiguration = createConfiguration(configurationContainer, name, objectFactory)
        override val capabilities: Set<Capability> get() = imageConfiguration.outgoing.capabilities.toSet()
        private val bundles = objectFactory.domainObjectSet(Bundle::class)
        private var platformBundles: MutableMap<OciExtension.Platform, Bundle>? = null
        override val component = createComponent(providerFactory)
        private val componentTask = createComponentTask(name, taskContainer, projectLayout)

        init {
            registerArtifacts(providerFactory)
        }

        private fun registerArtifacts(providerFactory: ProviderFactory) {
            imageConfiguration.outgoing.artifact(componentTask)
            imageConfiguration.outgoing.artifacts(providerFactory.provider {
                val linkedSet = linkedSetOf<TaskProvider<OciLayerTask>>()
                getBundleOrPlatformBundles().collectLayerTasks(linkedSet)
                linkedSet.map { taskProvider -> taskProvider.flatMap { it.tarFile } }
            })
        }

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

        private fun createComponent(providerFactory: ProviderFactory): Provider<OciComponent> {
            var provider = OciComponent.Builder().let { providerFactory.provider { it } }

            provider = provider.zip(providerFactory.provider {
                capabilities.map { OciComponent.Capability(it.group, it.name) }.toSet()
            }, OciComponent.Builder::capabilities)

            provider = provider.zip(providerFactory.provider {
                getBundleOrPlatformBundles()
            }.flatMap {
                it.createComponentBundleOrPlatformBundles(providerFactory)
            }, OciComponent.Builder::bundleOrPlatformBundles)

            provider = provider.zip(indexAnnotations, OciComponent.Builder::indexAnnotations).orElse(provider)

            return provider.map { it.build() }
        }

        private fun createComponentTask(imageName: String, taskContainer: TaskContainer, projectLayout: ProjectLayout) =
            taskContainer.register<OciComponentTask>(createComponentTaskName(imageName)) {
                component.set(this@ImageDefinition.component)
                componentFile.set(projectLayout.buildDirectory.file("oci/$imageName/component.json"))
            }

        private fun createComponentTaskName(imageName: String) =
            if (imageName == "main") "ociComponent" else "${imageName}OciComponent"


        abstract class Capabilities @Inject constructor(
            private val imageConfiguration: Configuration,
        ) : OciExtension.ImageDefinition.Capabilities {

            override fun capability(group: String, name: String) =
                imageConfiguration.outgoing.capability("$group:$name:default")
        }


        sealed interface BundleOrPlatformBundles {
            fun collectLayerTasks(set: LinkedHashSet<TaskProvider<OciLayerTask>>)
            fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<out OciComponent.BundleOrPlatformBundles>
        }


        abstract class Bundle @Inject constructor(
            private val imageName: String,
            imageConfiguration: Configuration,
            private val objectFactory: ObjectFactory,
            private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
        ) : OciExtension.ImageDefinition.Bundle, BundleOrPlatformBundles {

            override val parentImages = objectFactory.newInstance<ParentImages>(imageConfiguration)
            override val layers = objectFactory.namedDomainObjectList(OciExtension.ImageDefinition.Bundle.Layer::class)

            init {
                entryPoint.convention(null)
                arguments.convention(null)
            }

            override fun parentImages(configuration: Action<in OciExtension.ImageDefinition.Bundle.ParentImages>) =
                configuration.execute(parentImages)

            override fun layers(configuration: Action<in OciExtension.ImageDefinition.Bundle.Layers>) =
                configuration.execute(objectFactory.newInstance<Layers>(imageName, layers))

            override fun collectLayerTasks(set: LinkedHashSet<TaskProvider<OciLayerTask>>) {
                for (layer in layers) {
                    layer as Layer
                    val task = layer.task
                    if (task != null) {
                        set.add(task)
                    }
                }
            }

            override fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.Bundle> {
                val parentCapabilities = mutableListOf<OciComponent.Capability>()
                for (dependency in parentImages.dependencies) {
                    val capabilities = dependency.requestedCapabilities
                    if (capabilities.isEmpty()) { // add default capability
                        if (dependency is ProjectDependency) {
                            val id = projectDependencyPublicationResolver.resolve(
                                ModuleVersionIdentifier::class.java,
                                dependency
                            )
                            parentCapabilities.add(OciComponent.Capability(id.group, id.name))
                        } else {
                            parentCapabilities.add(OciComponent.Capability(dependency.group ?: "", dependency.name))
                        }
                    } else {
                        for (capability in capabilities) {
                            parentCapabilities.add(OciComponent.Capability(capability.group, capability.name))
                        }
                    }
                }

                var provider = OciComponent.BundleBuilder().parentCapabilities(parentCapabilities).let { providerFactory.provider { it } }

                provider = provider.zip(creationTime, OciComponent.BundleBuilder::creationTime).orElse(provider)
                provider = provider.zip(author, OciComponent.BundleBuilder::author).orElse(provider)
                provider = provider.zip(user, OciComponent.BundleBuilder::user).orElse(provider)
                provider = provider.zip(ports, OciComponent.BundleBuilder::ports).orElse(provider)
                provider = provider.zip(environment, OciComponent.BundleBuilder::environment).orElse(provider)

                var commandProvider = OciComponent.CommandBuilder().let { providerFactory.provider { it } }
                commandProvider = commandProvider.zip(entryPoint, OciComponent.CommandBuilder::entryPoint).orElse(commandProvider)
                commandProvider = commandProvider.zip(arguments, OciComponent.CommandBuilder::arguments).orElse(commandProvider)
                provider = provider.zip(commandProvider) { bundleBuilder, commandBuilder -> bundleBuilder.command(commandBuilder.build()) }

                provider = provider.zip(volumes, OciComponent.BundleBuilder::volumes).orElse(provider)
                provider = provider.zip(workingDirectory, OciComponent.BundleBuilder::workingDirectory).orElse(provider)
                provider = provider.zip(stopSignal, OciComponent.BundleBuilder::stopSignal).orElse(provider)
                provider = provider.zip(configAnnotations, OciComponent.BundleBuilder::configAnnotations).orElse(provider)
                provider = provider.zip(configDescriptorAnnotations, OciComponent.BundleBuilder::configDescriptorAnnotations).orElse(provider)
                provider = provider.zip(manifestAnnotations, OciComponent.BundleBuilder::manifestAnnotations).orElse(provider)
                provider = provider.zip(manifestDescriptorAnnotations, OciComponent.BundleBuilder::manifestDescriptorAnnotations).orElse(provider)

                var layersProvider = arrayOfNulls<OciComponent.Bundle.Layer>(layers.size).let { providerFactory.provider { it } }
                for ((i, layer) in layers.withIndex()) {
                    layer as Layer
                    layersProvider = layersProvider.zip(layer.createComponentLayer(providerFactory)) { layers, cLayer ->
                        layers[i] = cLayer
                        layers
                    }
                }
                provider = provider.zip(layersProvider) { bundleBuilder, layers ->
                    bundleBuilder.layers(List(layers.size) { i -> layers[i]!! })
                }

                return provider.map { it.build() }
            }


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
                private val layers: NamedDomainObjectList<OciExtension.ImageDefinition.Bundle.Layer>,
                private val objectFactory: ObjectFactory,
            ) : OciExtension.ImageDefinition.Bundle.Layers {

                override fun layer(name: String, configuration: Action<in OciExtension.ImageDefinition.Bundle.Layer>) {
                    val layer = objectFactory.newInstance<Layer>(name, imageName)
                    layers.add(layer)
                    configuration.execute(layer)
                }
            }


            abstract class Layer @Inject constructor(
                private val name: String,
                private val imageName: String,
                private val taskContainer: TaskContainer,
                private val projectLayout: ProjectLayout,
            ) : OciExtension.ImageDefinition.Bundle.Layer {

                var task: TaskProvider<OciLayerTask>? = null
                    private set

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

                fun createComponentLayer(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Layer> {
                    var provider = OciComponent.LayerBuilder().let { providerFactory.provider { it } }

                    provider = provider.zip(creationTime, OciComponent.LayerBuilder::creationTime).orElse(provider)
                    provider = provider.zip(author, OciComponent.LayerBuilder::author).orElse(provider)
                    provider = provider.zip(createdBy, OciComponent.LayerBuilder::createdBy).orElse(provider)
                    provider = provider.zip(comment, OciComponent.LayerBuilder::comment).orElse(provider)

                    var descriptorProvider = OciComponent.LayerDescriptorBuilder().let { providerFactory.provider { it } }
                    descriptorProvider = descriptorProvider.zip(annotations, OciComponent.LayerDescriptorBuilder::annotations).orElse(descriptorProvider)
                    val task = task
                    if (task != null) {
                        descriptorProvider = descriptorProvider.zip(task.flatMap { it.digestFile }.map { it.asFile.readText() }, OciComponent.LayerDescriptorBuilder::digest).orElse(descriptorProvider)
                        descriptorProvider = descriptorProvider.zip(task.flatMap { it.diffIdFile }.map { it.asFile.readText() }, OciComponent.LayerDescriptorBuilder::diffId).orElse(descriptorProvider)
                        descriptorProvider = descriptorProvider.zip(task.flatMap { it.tarFile }.map { it.asFile.length() }, OciComponent.LayerDescriptorBuilder::size).orElse(descriptorProvider)
                    }
                    provider = provider.zip(descriptorProvider) { layerBuilder, descriptorBuilder -> layerBuilder.descriptor(descriptorBuilder.build()) }

                    return provider.map { it.build() }
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
            projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
        ) : Bundle(imageName, imageConfiguration, objectFactory, projectDependencyPublicationResolver)


        class PlatformBundles(val map: Map<OciExtension.Platform, Bundle>) : BundleOrPlatformBundles {
            // TODO maybe remove PlatformBundles here completely as bundles collection and map should be sufficient

            override fun collectLayerTasks(set: LinkedHashSet<TaskProvider<OciLayerTask>>) {
                for (bundle in map.values) {
                    bundle.collectLayerTasks(set)
                }
            }

            override fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.PlatformBundles> {
                var provider = providerFactory.provider { mutableMapOf<OciComponent.Platform, OciComponent.Bundle>() }
                for ((platform, bundle) in map) {
                    val cPlatform = OciComponent.Platform(
                        platform.architecture, platform.os, platform.osVersion, platform.osFeatures, platform.variant
                    )
                    provider = provider.zip(bundle.createComponentBundleOrPlatformBundles(providerFactory)) { map, cBundle ->
                        map[cPlatform] = cBundle
                        map
                    }
                }
                return provider.map { OciComponent.PlatformBundles(it) }
            }
        }
    }
}