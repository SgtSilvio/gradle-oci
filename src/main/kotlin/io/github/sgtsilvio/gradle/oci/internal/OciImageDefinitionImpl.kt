package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciComponentTask
import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.platform.AllPlatformFilter
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
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

abstract class OciImageDefinitionImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    configurationContainer: ConfigurationContainer,
    taskContainer: TaskContainer,
    projectLayout: ProjectLayout,
    private val project: Project,
) : OciImageDefinition {

    private val imageConfiguration = createConfiguration(configurationContainer, name, objectFactory)
    override val capabilities = objectFactory.newInstance<Capabilities>(imageConfiguration)
    private val bundles = objectFactory.domainObjectSet(Bundle::class)
    private var allPlatformBundleScope: BundleScope? = null
    private var platformBundleScopes: HashMap<PlatformFilter, BundleScope>? = null
    private var universalBundle: Bundle? = null
    private var platformBundles: TreeMap<Platform, Bundle>? = null
    override val component = createComponent(providerFactory)
    private val componentTask = createComponentTask(name, taskContainer, projectLayout)

    init {
        registerArtifacts(providerFactory)
    }

    private fun registerArtifacts(providerFactory: ProviderFactory) {
        imageConfiguration.outgoing.artifact(componentTask)
        imageConfiguration.outgoing.artifacts(providerFactory.provider {
            val linkedMap = LinkedHashMap<String, TaskProvider<OciLayerTask>>()
            getBundleOrPlatformBundles().collectLayerTasks(linkedMap)
            linkedMap.map { (_, taskProvider) -> taskProvider.flatMap { it.tarFile } }
        })
    }

    override fun getName() = name

    override fun capabilities(configuration: Action<in OciImageDefinition.Capabilities>) =
        configuration.execute(capabilities)

    override fun allPlatforms(configuration: Action<in OciImageDefinition.BundleScope>) {
        var bundleScope = allPlatformBundleScope
        if (bundleScope == null) {
            bundleScope = objectFactory.newInstance<BundleScope>(AllPlatformFilter, name, bundles)
            allPlatformBundleScope = bundleScope
        }
        configuration.execute(bundleScope)
    }

    override fun platformsMatching(
        platformFilter: PlatformFilter,
        configuration: Action<in OciImageDefinition.BundleScope>,
    ) {
        if (platformFilter == AllPlatformFilter) {
            return allPlatforms(configuration)
        }
        var bundleScopes = platformBundleScopes
        var bundleScope: BundleScope? = null
        if (bundleScopes == null) {
            bundleScopes = HashMap(4)
            platformBundleScopes = bundleScopes
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
        getOrCreatePlatformBundle(platform)
    }

    override fun specificPlatform(platform: Platform, configuration: Action<in OciImageDefinition.Bundle>) =
        configuration.execute(getOrCreatePlatformBundle(platform))

    private fun getOrCreatePlatformBundle(platform: Platform): Bundle {
        var platformBundles = platformBundles
        if (platformBundles == null) {
            platformBundles = TreeMap()
            this.platformBundles = platformBundles
        }
        var bundle = platformBundles[platform]
        if (bundle == null) {
            bundle = objectFactory.newInstance<Bundle>(name, imageConfiguration, Optional.of(platform))
            bundles.add(bundle)
            platformBundles[platform] = bundle
        }
        return bundle
    }

    private fun getBundleOrPlatformBundles(): BundleOrPlatformBundles {
        val platformBundles = platformBundles
        if (platformBundles != null) {
            return PlatformBundles(platformBundles)
        }
        var universalBundle = universalBundle
        if (universalBundle == null) {
            universalBundle = objectFactory.newInstance<Bundle>(name, imageConfiguration, Optional.empty<Bundle>())
            bundles.add(universalBundle)
            this.universalBundle = universalBundle
        }
        return universalBundle
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

    private fun createComponent(providerFactory: ProviderFactory): Provider<OciComponent> =
        providerFactory.provider { OciComponentBuilder() }
            .zip(createComponentCapabilities(providerFactory), OciComponentBuilder::capabilities)
            .zip(createComponentBundleOrPlatformBundles(providerFactory), OciComponentBuilder::bundleOrPlatformBundles)
            .zipAbsentAsEmptyMap(indexAnnotations, OciComponentBuilder::indexAnnotations)
            .map { it.build() }

    private fun createComponentCapabilities(providerFactory: ProviderFactory) = providerFactory.provider {
        capabilities.set.map { OciComponent.Capability(it.group, it.name) }.toSet()
            .ifEmpty { setOf(OciComponent.Capability(project.group.toString(), project.name)) }
    }

    private fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory) =
        providerFactory.provider { getBundleOrPlatformBundles() }
            .flatMap { it.createComponentBundleOrPlatformBundles(providerFactory) }

    private fun createComponentTask(imageName: String, taskContainer: TaskContainer, projectLayout: ProjectLayout) =
        taskContainer.register<OciComponentTask>(createComponentTaskName(imageName)) {
            component.set(this@OciImageDefinitionImpl.component)
            componentFile.set(projectLayout.buildDirectory.file("oci/$imageName/component.json"))
        }

    private fun createComponentTaskName(imageName: String) =
        if (imageName == "main") "ociComponent" else "${imageName}OciComponent"


    abstract class Capabilities @Inject constructor(
        private val imageConfiguration: Configuration,
    ) : OciImageDefinition.Capabilities {

        override val set: Set<Capability> get() = imageConfiguration.outgoing.capabilities.toSet()

        override fun add(group: String, name: String) = imageConfiguration.outgoing.capability("$group:$name:default")
    }


    sealed interface BundleOrPlatformBundles {
        fun collectLayerTasks(linkedMap: LinkedHashMap<String, TaskProvider<OciLayerTask>>)
        fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<out OciComponent.BundleOrPlatformBundles>
    }


    abstract class Bundle @Inject constructor(
        imageName: String,
        imageConfiguration: Configuration,
        platform: Optional<Platform>,
        objectFactory: ObjectFactory,
        private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
    ) : OciImageDefinition.Bundle, BundleOrPlatformBundles {

        val platform: Platform? = platform.orElse(null)
        override val parentImages = objectFactory.newInstance<ParentImages>(imageConfiguration)
        override val config = objectFactory.newInstance<OciImageDefinition.Bundle.Config>().apply {
            entryPoint.convention(null)
            arguments.convention(null)
        }
        override val layers = objectFactory.newInstance<Layers>(imageName, platform)

        override fun parentImages(configuration: Action<in OciImageDefinition.Bundle.ParentImages>) =
            configuration.execute(parentImages)

        override fun config(configuration: Action<in OciImageDefinition.Bundle.Config>) = configuration.execute(config)

        override fun layers(configuration: Action<in OciImageDefinition.Bundle.Layers>) = configuration.execute(layers)

        override fun collectLayerTasks(linkedMap: LinkedHashMap<String, TaskProvider<OciLayerTask>>) {
            for (layer in layers.list) {
                layer as Layer
                val task = layer.getTask()
                if (task != null) {
                    linkedMap.putIfAbsent(task.name, task)
                }
            }
        }

        override fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.Bundle> =
            providerFactory.provider { OciComponentBundleBuilder() }
                .zip(createComponentParentCapabilities(providerFactory), OciComponentBundleBuilder::parentCapabilities)
                .zipAbsentAsNull(config.creationTime, OciComponentBundleBuilder::creationTime)
                .zipAbsentAsNull(config.author, OciComponentBundleBuilder::author)
                .zipAbsentAsNull(config.user, OciComponentBundleBuilder::user)
                .zipAbsentAsEmptySet(config.ports, OciComponentBundleBuilder::ports)
                .zipAbsentAsEmptyMap(config.environment, OciComponentBundleBuilder::environment)
                .zip(createComponentCommand(providerFactory)) { bundleBuilder, commandBuilder ->
                    bundleBuilder.command(commandBuilder.build())
                }
                .zipAbsentAsEmptySet(config.volumes, OciComponentBundleBuilder::volumes)
                .zipAbsentAsNull(config.workingDirectory, OciComponentBundleBuilder::workingDirectory)
                .zipAbsentAsNull(config.stopSignal, OciComponentBundleBuilder::stopSignal)
                .zipAbsentAsEmptyMap(config.configAnnotations, OciComponentBundleBuilder::configAnnotations)
                .zipAbsentAsEmptyMap(
                    config.configDescriptorAnnotations,
                    OciComponentBundleBuilder::configDescriptorAnnotations,
                )
                .zipAbsentAsEmptyMap(config.manifestAnnotations, OciComponentBundleBuilder::manifestAnnotations)
                .zipAbsentAsEmptyMap(
                    config.manifestDescriptorAnnotations,
                    OciComponentBundleBuilder::manifestDescriptorAnnotations,
                )
                .zip(createComponentLayers(providerFactory), OciComponentBundleBuilder::layers)
                .map { it.build() }

        private fun createComponentParentCapabilities(providerFactory: ProviderFactory): Provider<List<OciComponent.Capability>> =
            providerFactory.provider {
                val parentCapabilities = mutableListOf<OciComponent.Capability>()
                for (dependency in parentImages.dependencies) {
                    val capabilities = dependency.requestedCapabilities
                    if (capabilities.isEmpty()) { // add default capability
                        if (dependency is ProjectDependency) {
                            val id = projectDependencyPublicationResolver.resolve(
                                ModuleVersionIdentifier::class.java,
                                dependency,
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
                parentCapabilities
            }

        private fun createComponentCommand(providerFactory: ProviderFactory) =
            providerFactory.provider { OciComponentBundleCommandBuilder() }
                .zipAbsentAsNull(config.entryPoint, OciComponentBundleCommandBuilder::entryPoint)
                .zipAbsentAsNull(config.arguments, OciComponentBundleCommandBuilder::arguments)

        private fun createComponentLayers(providerFactory: ProviderFactory): Provider<List<OciComponent.Bundle.Layer>> =
            providerFactory.provider {
                var listProvider = providerFactory.provider { listOf<OciComponent.Bundle.Layer>() }
                for (layer in layers.list) {
                    layer as Layer
                    listProvider = listProvider.zip(layer.createComponentLayer(providerFactory)) { layers, cLayer ->
                        layers + cLayer
                    }
                }
                listProvider
            }.flatMap { it }


        abstract class ParentImages @Inject constructor(
            private val imageConfiguration: Configuration,
            private val dependencyHandler: DependencyHandler,
        ) : OciImageDefinition.Bundle.ParentImages {

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

            override fun <D : ModuleDependency> add(dependencyProvider: Provider<out D>, configuration: Action<in D>) {
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
            platform: Optional<Platform>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.Bundle.Layers {

            private val platform: Platform? = platform.orElse(null)
            override val list = objectFactory.namedDomainObjectList(OciImageDefinition.Bundle.Layer::class)

            override fun layer(name: String, configuration: Action<in OciImageDefinition.Bundle.Layer>) =
                configuration.execute(layer(name))

            fun layer(name: String): Layer {
                var layer = list.findByName(name) as Layer?
                if (layer == null) {
                    layer = objectFactory.newInstance<Layer>(name, imageName, Optional.ofNullable(platform))
                    list.add(layer)
                }
                return layer
            }
        }


        abstract class Layer @Inject constructor(
            private val name: String,
            private val imageName: String,
            platform: Optional<Platform>,
            objectFactory: ObjectFactory,
            private val taskContainer: TaskContainer,
            private val projectLayout: ProjectLayout,
        ) : OciImageDefinition.Bundle.Layer {

            private val platform: Platform? = platform.orElse(null)
            override val metadata = objectFactory.newInstance<OciImageDefinition.Bundle.Layer.Metadata>().apply {
                createdBy.convention("gradle-oci: $name")
            }

            private var task: TaskProvider<OciLayerTask>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            override fun getName() = name

            override fun metadata(configuration: Action<in OciImageDefinition.Bundle.Layer.Metadata>) =
                configuration.execute(metadata)

            override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                var task = task
                if (task == null) {
                    task = taskContainer.createLayerTask(
                        imageName, name, platform?.toString() ?: "", projectLayout, configuration
                    )
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

            fun createComponentLayer(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Layer> =
                providerFactory.provider { OciComponentBundleLayerBuilder() }
                    .zipAbsentAsNull(metadata.creationTime, OciComponentBundleLayerBuilder::creationTime)
                    .zipAbsentAsNull(metadata.author, OciComponentBundleLayerBuilder::author)
                    .zipAbsentAsNull(metadata.createdBy, OciComponentBundleLayerBuilder::createdBy)
                    .zipAbsentAsNull(metadata.comment, OciComponentBundleLayerBuilder::comment)
                    .zip(createComponentLayerDescriptor(providerFactory)) { layerBuilder, descriptorBuilder ->
                        layerBuilder.descriptor(descriptorBuilder.build())
                    }
                    .map { it.build() }

            private fun createComponentLayerDescriptor(providerFactory: ProviderFactory): Provider<OciComponentBundleLayerDescriptorBuilder> {
                val task = providerFactory.provider { getTask() }.flatMap { it }
                return providerFactory.provider { OciComponentBundleLayerDescriptorBuilder() }
                    .zipAbsentAsEmptyMap(metadata.annotations, OciComponentBundleLayerDescriptorBuilder::annotations)
                    .zipAbsentAsNull(
                        task.flatMap { it.digestFile }.map { it.asFile.readText() },
                        OciComponentBundleLayerDescriptorBuilder::digest,
                    )
                    .zipAbsentAsNull(
                        task.flatMap { it.diffIdFile }.map { it.asFile.readText() },
                        OciComponentBundleLayerDescriptorBuilder::diffId,
                    )
                    .zipAbsentAsNull(
                        task.flatMap { it.tarFile }.map { it.asFile.length() },
                        OciComponentBundleLayerDescriptorBuilder::size,
                    )
            }
        }
    }


    class PlatformBundles(val map: TreeMap<Platform, Bundle>) : BundleOrPlatformBundles {
        // TODO maybe remove PlatformBundles here completely as bundles collection and map should be sufficient

        override fun collectLayerTasks(linkedMap: LinkedHashMap<String, TaskProvider<OciLayerTask>>) {
            for (bundle in map.values) {
                bundle.collectLayerTasks(linkedMap)
            }
        }

        override fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.PlatformBundles> {
            var provider = providerFactory.provider { TreeMap<Platform, OciComponent.Bundle>() }
            for ((platform, bundle) in map) { // TODO not lazy
                provider =
                    provider.zip(bundle.createComponentBundleOrPlatformBundles(providerFactory)) { map, cBundle ->
                        map[platform] = cBundle
                        map
                    }
            }
            return provider.map { OciComponent.PlatformBundles(it) }
        }
    }


    abstract class BundleScope @Inject constructor(
        private val platformFilter: PlatformFilter,
        imageName: String,
        bundles: DomainObjectSet<Bundle>,
        objectFactory: ObjectFactory,
    ) : OciImageDefinition.BundleScope {

        private val filteredBundles = when (platformFilter) {
            AllPlatformFilter -> bundles
            else -> bundles.matching { bundle -> platformFilter.matches(bundle.platform) }
        }
        override val layers = objectFactory.newInstance<Layers>(platformFilter, imageName, filteredBundles)

        override fun parentImages(configuration: Action<in OciImageDefinition.Bundle.ParentImages>) =
            filteredBundles.configureEach { parentImages(configuration) }

        override fun config(configuration: Action<in OciImageDefinition.Bundle.Config>) =
            filteredBundles.configureEach { config(configuration) }

        override fun layers(configuration: Action<in OciImageDefinition.BundleScope.Layers>) =
            configuration.execute(layers)

        abstract class Layers @Inject constructor(
            private val platformFilter: PlatformFilter,
            private val imageName: String,
            private val bundles: DomainObjectSet<Bundle>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.BundleScope.Layers {

            override val list = objectFactory.namedDomainObjectList(OciImageDefinition.BundleScope.Layer::class)

            override fun layer(name: String, configuration: Action<in OciImageDefinition.BundleScope.Layer>) =
                configuration.execute(layer(name))

            fun layer(name: String): Layer {
                var layer = list.findByName(name) as Layer?
                if (layer == null) {
                    layer = objectFactory.newInstance<Layer>(name, platformFilter, imageName, bundles)
                    list.add(layer)
                }
                return layer
            }
        }

        abstract class Layer @Inject constructor(
            private val name: String,
            private val platformFilter: PlatformFilter,
            private val imageName: String,
            private val bundles: DomainObjectSet<Bundle>,
            private val projectLayout: ProjectLayout,
            private val taskContainer: TaskContainer,
        ) : OciImageDefinition.BundleScope.Layer {

            private var task: TaskProvider<OciLayerTask>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            override fun getName() = name

            override fun metadata(configuration: Action<in OciImageDefinition.Bundle.Layer.Metadata>) =
                bundles.configureEach { layers.layer(name).metadata(configuration) }

            override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                var task = task
                if (task == null) {
                    task = taskContainer.createLayerTask(
                        imageName, name, platformFilter.toString(), projectLayout, configuration
                    )
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
        }
    }
}

private fun TaskContainer.createLayerTask(
    imageName: String,
    layerName: String,
    platformString: String,
    projectLayout: ProjectLayout,
    configuration: Action<in OciCopySpec>,
) = register<OciLayerTask>(createLayerTaskName(imageName, layerName, platformString)) {
    outputDirectory.convention(projectLayout.buildDirectory.dir("oci/$imageName/$layerName$platformString"))
    contents(configuration)
}

private fun createLayerTaskName(imageName: String, layerName: String, platformString: String) =
    if (imageName == "main") "${layerName}OciLayer$platformString"
    else "$imageName${layerName.replaceFirstChar(Char::uppercaseChar)}OciLayer$platformString"