package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciComponentTask
import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciExtension
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.dsl.AllPlatformFilter
import io.github.sgtsilvio.gradle.oci.dsl.Platform
import io.github.sgtsilvio.gradle.oci.dsl.PlatformFilter
import io.github.sgtsilvio.gradle.oci.dsl.PlatformImpl
import org.gradle.api.Action
import org.gradle.api.DomainObjectSet
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
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciExtensionImpl @Inject constructor(private val objectFactory: ObjectFactory) : OciExtension {

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
        variant: String,
        osVersion: String,
        osFeatures: Set<String>,
    ) = PlatformImpl(os, architecture, variant, osVersion, osFeatures)

    override fun platformFilter(configuration: Action<in OciExtension.PlatformFilterBuilder>): PlatformFilter {
        val builder = objectFactory.newInstance<OciExtension.PlatformFilterBuilder>()
        configuration.execute(builder)
        return PlatformFilter(
            builder.oses.get(),
            builder.architectures.get(),
            builder.variants.get(),
            builder.osVersions.get(),
        )
    }

    override fun PlatformFilter.or(configuration: Action<in OciExtension.PlatformFilterBuilder>) =
        or(platformFilter(configuration))


    abstract class ImageDefinition @Inject constructor(
        private val name: String,
        private val objectFactory: ObjectFactory,
        providerFactory: ProviderFactory,
        configurationContainer: ConfigurationContainer,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
    ) : OciExtension.ImageDefinition {

        private val imageConfiguration = createConfiguration(configurationContainer, name, objectFactory)
        override val capabilities = objectFactory.newInstance<Capabilities>(imageConfiguration)
        private val bundles = objectFactory.domainObjectSet(Bundle::class)
        private var universalBundleScope: BundleScope? = null
        private var bundleScopes: MutableMap<PlatformFilter, BundleScope>? = null
        private var platformBundles: MutableMap<Platform, Bundle>? = null
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
            configuration.execute(capabilities)

        override fun allPlatforms(configuration: Action<in OciExtension.ImageDefinition.BundleScope>) {
            var bundleScope = universalBundleScope
            if (bundleScope == null) {
                bundleScope = objectFactory.newInstance<BundleScope>(AllPlatformFilter, name, bundles)
                universalBundleScope = bundleScope
            }
            configuration.execute(bundleScope)
        }

        override fun platformsMatching(
            platformFilter: PlatformFilter,
            configuration: Action<in OciExtension.ImageDefinition.BundleScope>,
        ) {
            if (platformFilter == AllPlatformFilter) {
                return allPlatforms(configuration)
            }
            var bundleScopes = bundleScopes
            var bundleScope: BundleScope? = null
            if (bundleScopes == null) {
                bundleScopes = mutableMapOf()
                this.bundleScopes = bundleScopes
            } else {
                bundleScope = bundleScopes[platformFilter]
            }
            if (bundleScope == null) {
                bundleScope = objectFactory.newInstance<BundleScope>(platformFilter, name, bundles)
                bundleScopes[platformFilter] = bundleScope
            }
            configuration.execute(bundleScope)
        }

        override fun specificPlatform(platform: Platform) {
            addPlatformInternal(platform)
        }

        override fun specificPlatform(
            platform: Platform,
            configuration: Action<in OciExtension.ImageDefinition.Bundle>,
        ) = configuration.execute(addPlatformInternal(platform))

        private fun addPlatformInternal(platform: Platform): Bundle {
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
                capabilities.set.map { OciComponent.Capability(it.group, it.name) }.toSet()
            }, OciComponent.Builder::capabilities)

            provider = provider.zip(providerFactory.provider {
                getBundleOrPlatformBundles()
            }.flatMap {
                it.createComponentBundleOrPlatformBundles(providerFactory)
            }, OciComponent.Builder::bundleOrPlatformBundles)

            provider = provider.zip(indexAnnotations.orElse(mapOf()), OciComponent.Builder::indexAnnotations)

            return provider.map { it.build() }
        }

        private fun createComponentTask(imageName: String, taskContainer: TaskContainer, projectLayout: ProjectLayout) =
            taskContainer.register<OciComponentTask>(createComponentTaskName(imageName)) {
                component.set(this@ImageDefinition.component)
                componentFile.set(projectLayout.buildDirectory.file("oci/$imageName/component.json"))
            }

        private fun createComponentTaskName(imageName: String) =
            if (imageName == "main") "ociComponent" else "${imageName}OciComponent"


        abstract class BundleScope @Inject constructor(
            private val platformFilter: PlatformFilter,
            imageName: String,
            bundles: DomainObjectSet<Bundle>,
            objectFactory: ObjectFactory,
        ) : OciExtension.ImageDefinition.BundleScope {

            private val filteredBundles = if (platformFilter == AllPlatformFilter) bundles else
                bundles.matching { bundle ->
                    if (bundle is PlatformBundle) platformFilter.isSatisfiedBy(bundle.platform) else false
                }
            private val layers = objectFactory.newInstance<LayersScope>(platformFilter, imageName, filteredBundles)

            override fun parentImages(configuration: Action<in OciExtension.ImageDefinition.Bundle.ParentImages>) =
                filteredBundles.configureEach { parentImages(configuration) }

            override fun config(configuration: Action<in OciExtension.ImageDefinition.Bundle.Config>) =
                filteredBundles.configureEach { config(configuration) }

            override fun layers(configuration: Action<in OciExtension.ImageDefinition.BundleScope.LayersScope>) =
                configuration.execute(layers)

            abstract class LayersScope @Inject constructor(
                private val platformFilter: PlatformFilter,
                private val imageName: String,
                private val bundles: DomainObjectSet<Bundle>,
                private val objectFactory: ObjectFactory,
            ) : OciExtension.ImageDefinition.BundleScope.LayersScope {

                private val list = objectFactory.namedDomainObjectList(OciExtension.ImageDefinition.BundleScope.LayerScope::class)

                override fun layer(
                    name: String,
                    configuration: Action<in OciExtension.ImageDefinition.BundleScope.LayerScope>,
                ) = configuration.execute(layer(name))

                fun layer(name: String): LayerScope {
                    var layer = list.findByName(name) as LayerScope?
                    if (layer == null) {
                        layer = objectFactory.newInstance<LayerScope>(name, platformFilter, imageName, bundles)
                        list.add(layer)
                    }
                    return layer
                }
            }

            abstract class LayerScope @Inject constructor(
                private val name: String,
                private val platformFilter: PlatformFilter,
                private val imageName: String,
                private val bundles: DomainObjectSet<Bundle>,
                private val projectLayout: ProjectLayout,
                private val taskContainer: TaskContainer,
            ) : OciExtension.ImageDefinition.BundleScope.LayerScope {

                private var task: TaskProvider<OciLayerTask>? = null
                private var externalTask: TaskProvider<OciLayerTask>? = null

                override fun getName() = name

                override fun metadata(configuration: Action<in OciExtension.ImageDefinition.Bundle.Layer.Metadata>) =
                    bundles.configureEach { layers.layer(name).metadata(configuration) }

                override fun contents(configuration: Action<in OciCopySpec>) {
                    if (externalTask != null) {
                        throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                    }
                    var task = task
                    if (task == null) {
                        task = createTask(configuration)
                        this.task = task
                        bundles.configureEach {
                            layers.layer(name).contents(task)
                        }
                    } else {
                        task.configure {
                            contents(configuration)
                        }
                    }
                }

                override fun contents(task: TaskProvider<OciLayerTask>) {
                    externalTask = if (task == this.task) null else task
                    bundles.configureEach {
                        layers.layer(name).contents(task)
                    }
                }

                private fun createTask(configuration: Action<in OciCopySpec>): TaskProvider<OciLayerTask> {
                    val imageName = imageName
                    val layerName = name
                    val platformFilter = platformFilter.toString()
                    return taskContainer.register<OciLayerTask>(createTaskName(imageName, layerName, platformFilter)) {
                        outputDirectory.convention(projectLayout.buildDirectory.dir("oci/$imageName/$layerName$platformFilter"))
                        contents(configuration)
                    }
                }

                private fun createTaskName(imageName: String, layerName: String, platformFilter: String) =
                    if (imageName == "main") "${layerName}OciLayer$platformFilter"
                    else "$imageName${layerName.capitalize()}OciLayer$platformFilter"
            }
        }


        abstract class Capabilities @Inject constructor(
            private val imageConfiguration: Configuration,
        ) : OciExtension.ImageDefinition.Capabilities {

            override val set: Set<Capability> get() = imageConfiguration.outgoing.capabilities.toSet()

            override fun add(group: String, name: String) =
                imageConfiguration.outgoing.capability("$group:$name:default")
        }


        sealed interface BundleOrPlatformBundles {
            fun collectLayerTasks(set: LinkedHashSet<TaskProvider<OciLayerTask>>)
            fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<out OciComponent.BundleOrPlatformBundles>
        }


        abstract class Bundle @Inject constructor(
            imageName: String,
            imageConfiguration: Configuration,
            objectFactory: ObjectFactory,
            private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
        ) : OciExtension.ImageDefinition.Bundle, BundleOrPlatformBundles {

            override val parentImages = objectFactory.newInstance<ParentImages>(imageConfiguration)
            override val config = objectFactory.newInstance<OciExtension.ImageDefinition.Bundle.Config>().apply {
                entryPoint.convention(null)
                arguments.convention(null)
            }
            override val layers = objectFactory.newInstance<Layers>(imageName)

            override fun parentImages(configuration: Action<in OciExtension.ImageDefinition.Bundle.ParentImages>) =
                configuration.execute(parentImages)

            override fun config(configuration: Action<in OciExtension.ImageDefinition.Bundle.Config>) =
                configuration.execute(config)

            override fun layers(configuration: Action<in OciExtension.ImageDefinition.Bundle.Layers>) =
                configuration.execute(layers)

            override fun collectLayerTasks(set: LinkedHashSet<TaskProvider<OciLayerTask>>) {
                for (layer in layers.list) {
                    layer as Layer
                    val task = layer.getTask()
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

                provider = provider.zipOptional(config.creationTime, OciComponent.BundleBuilder::creationTime)
                provider = provider.zipOptional(config.author, OciComponent.BundleBuilder::author)
                provider = provider.zipOptional(config.user, OciComponent.BundleBuilder::user)
                provider = provider.zip(config.ports.orElse(setOf()), OciComponent.BundleBuilder::ports)
                provider = provider.zip(config.environment.orElse(mapOf()), OciComponent.BundleBuilder::environment)

                var commandProvider = OciComponent.CommandBuilder().let { providerFactory.provider { it } }
                commandProvider = commandProvider.zipOptional(config.entryPoint, OciComponent.CommandBuilder::entryPoint)
                commandProvider = commandProvider.zipOptional(config.arguments, OciComponent.CommandBuilder::arguments)
                provider = provider.zip(commandProvider) { bundleBuilder, commandBuilder -> bundleBuilder.command(commandBuilder.build()) }

                provider = provider.zip(config.volumes.orElse(setOf()), OciComponent.BundleBuilder::volumes)
                provider = provider.zipOptional(config.workingDirectory, OciComponent.BundleBuilder::workingDirectory)
                provider = provider.zipOptional(config.stopSignal, OciComponent.BundleBuilder::stopSignal)
                provider = provider.zip(config.configAnnotations.orElse(mapOf()), OciComponent.BundleBuilder::configAnnotations)
                provider = provider.zip(config.configDescriptorAnnotations.orElse(mapOf()), OciComponent.BundleBuilder::configDescriptorAnnotations)
                provider = provider.zip(config.manifestAnnotations.orElse(mapOf()), OciComponent.BundleBuilder::manifestAnnotations)
                provider = provider.zip(config.manifestDescriptorAnnotations.orElse(mapOf()), OciComponent.BundleBuilder::manifestDescriptorAnnotations)

                var layersProvider = arrayOfNulls<OciComponent.Bundle.Layer>(layers.list.size).let { providerFactory.provider { it } }
                for ((i, layer) in layers.list.withIndex()) {
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
                private val objectFactory: ObjectFactory,
            ) : OciExtension.ImageDefinition.Bundle.Layers {

                override val list = objectFactory.namedDomainObjectList(OciExtension.ImageDefinition.Bundle.Layer::class)

                override fun layer(name: String, configuration: Action<in OciExtension.ImageDefinition.Bundle.Layer>) =
                    configuration.execute(layer(name))

                fun layer(name: String): Layer {
                    var layer = list.findByName(name) as Layer?
                    if (layer == null) {
                        layer = objectFactory.newInstance<Layer>(name, imageName)
                        list.add(layer)
                    }
                    return layer
                }
            }


            abstract class Layer @Inject constructor(
                private val name: String,
                private val imageName: String,
                objectFactory: ObjectFactory,
                private val taskContainer: TaskContainer,
                private val projectLayout: ProjectLayout,
            ) : OciExtension.ImageDefinition.Bundle.Layer {

                override val metadata = objectFactory.newInstance<OciExtension.ImageDefinition.Bundle.Layer.Metadata>().apply {
                    createdBy.convention("gradle-oci: $name")
                }

                private var task: TaskProvider<OciLayerTask>? = null
                private var externalTask: TaskProvider<OciLayerTask>? = null

                override fun getName() = name

                override fun metadata(configuration: Action<in OciExtension.ImageDefinition.Bundle.Layer.Metadata>) =
                    configuration.execute(metadata)

                override fun contents(configuration: Action<in OciCopySpec>) {
                    if (externalTask != null) {
                        throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                    }
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
                    externalTask = if (task == this.task) null else task
                }

                fun getTask() = externalTask ?: task

                fun createComponentLayer(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Layer> {
                    var provider = OciComponent.LayerBuilder().let { providerFactory.provider { it } }

                    provider = provider.zipOptional(metadata.creationTime, OciComponent.LayerBuilder::creationTime)
                    provider = provider.zipOptional(metadata.author, OciComponent.LayerBuilder::author)
                    provider = provider.zipOptional(metadata.createdBy, OciComponent.LayerBuilder::createdBy)
                    provider = provider.zipOptional(metadata.comment, OciComponent.LayerBuilder::comment)

                    var descriptorProvider = OciComponent.LayerDescriptorBuilder().let { providerFactory.provider { it } }
                    descriptorProvider = descriptorProvider.zip(metadata.annotations.orElse(mapOf()), OciComponent.LayerDescriptorBuilder::annotations)
                    val task = getTask()
                    if (task != null) {
                        descriptorProvider = descriptorProvider.zipOptional(task.flatMap { it.digestFile }.map { it.asFile.readText() }, OciComponent.LayerDescriptorBuilder::digest)
                        descriptorProvider = descriptorProvider.zipOptional(task.flatMap { it.diffIdFile }.map { it.asFile.readText() }, OciComponent.LayerDescriptorBuilder::diffId)
                        descriptorProvider = descriptorProvider.zipOptional(task.flatMap { it.tarFile }.map { it.asFile.length() }, OciComponent.LayerDescriptorBuilder::size)
                    }
                    provider = provider.zip(descriptorProvider) { layerBuilder, descriptorBuilder -> layerBuilder.descriptor(descriptorBuilder.build()) }

                    return provider.map { it.build() }
                }

                private fun createTaskName(imageName: String, name: String) =
                    if (imageName == "main") "${name}OciLayer" else "${imageName}${name.capitalize()}OciLayer" // TODO platform as part of name
            }
        }


        abstract class PlatformBundle @Inject constructor(
            imageName: String,
            imageConfiguration: Configuration,
            val platform: Platform?,
            objectFactory: ObjectFactory,
            projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
        ) : Bundle(imageName, imageConfiguration, objectFactory, projectDependencyPublicationResolver)


        class PlatformBundles(val map: Map<Platform, Bundle>) : BundleOrPlatformBundles {
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