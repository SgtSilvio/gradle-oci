package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciComponentTask
import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_CATEGORY
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.internal.gradle.LazyPublishArtifact
import io.github.sgtsilvio.gradle.oci.internal.gradle.addArtifacts
import io.github.sgtsilvio.gradle.oci.internal.gradle.getDefaultCapability
import io.github.sgtsilvio.gradle.oci.internal.gradle.zipAbsentAsNull
import io.github.sgtsilvio.gradle.oci.mapping.defaultMappedImageNamespace
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
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
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import java.util.*
import javax.inject.Inject

internal abstract class OciImageDefinitionImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    configurationContainer: ConfigurationContainer,
    taskContainer: TaskContainer,
    projectLayout: ProjectLayout,
    private val project: Project,
) : OciImageDefinition {

    final override val configuration = createConfiguration(configurationContainer, name, objectFactory)
    final override val imageName: Property<String> =
        objectFactory.property<String>().convention(providerFactory.provider {
            defaultMappedImageNamespace(project.group.toString()) + project.name
        })
    final override val imageTag: Property<String> =
        objectFactory.property<String>().convention(providerFactory.provider {
            project.version.toString().concatKebabCase(name.mainToEmpty().kebabCase())
        })
    final override val capabilities = objectFactory.newInstance<Capabilities>(configuration.outgoing, name)
    private val bundles = objectFactory.domainObjectSet(Bundle::class)
    private var allPlatformBundleScope: BundleScope? = null
    private var platformBundleScopes: HashMap<PlatformFilter, BundleScope>? = null
    private var universalBundle: Bundle? = null
    private var platformBundles: TreeMap<Platform, Bundle>? = null
    final override val component = createComponent(providerFactory)
    private val componentTask = createComponentTask(name, taskContainer, projectLayout)
    final override val dependency = createDependency()

    init {
        registerArtifacts(objectFactory, providerFactory)
    }

    private fun createConfiguration(
        configurationContainer: ConfigurationContainer,
        imageDefName: String,
        objectFactory: ObjectFactory,
    ): Configuration = configurationContainer.create(createOciVariantName(imageDefName)) {
        description = "OCI elements for $imageDefName"
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
//            attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
        }
    }

    private fun createComponent(providerFactory: ProviderFactory): Provider<OciComponent> =
        providerFactory.provider { OciComponentBuilder() }
            .zip(createImageReference(), OciComponentBuilder::imageReference)
            .zip(createComponentCapabilities(), OciComponentBuilder::capabilities)
            .zip(createComponentBundleOrPlatformBundles(providerFactory), OciComponentBuilder::bundleOrPlatformBundles)
            .zip(indexAnnotations.orElse(emptyMap()), OciComponentBuilder::indexAnnotations)
            .map { it.build() }

    private fun createImageReference(): Provider<OciImageReference> =
        imageName.zip(imageTag) { name, tag -> OciImageReference(name, tag) }

    private fun createComponentCapabilities(): Provider<Set<VersionedCoordinates>> =
        capabilities.set.map { capabilities ->
            capabilities.map { VersionedCoordinates(it.group, it.name, it.version!!) }.toSet().ifEmpty {
                setOf(VersionedCoordinates(project.group.toString(), project.name, project.version.toString()))
            }
        }

    private fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.BundleOrPlatformBundles> =
        providerFactory.provider { getBundleOrPlatformBundles() }
            .flatMap { it.createComponentBundleOrPlatformBundles(providerFactory) }

    private fun createComponentTask(imageDefName: String, taskContainer: TaskContainer, projectLayout: ProjectLayout) =
        taskContainer.register<OciComponentTask>(createOciComponentClassifier(imageDefName).camelCase()) {
            group = TASK_GROUP_NAME
            description = "Assembles an OCI component json file for the $imageDefName image."
            encodedComponent.set(this@OciImageDefinitionImpl.component.map { it.encodeToJsonString() })
            destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
            classifier.set(createOciComponentClassifier(imageDefName))
        }

    private fun createDependency(): Provider<ProjectDependency> = capabilities.set.map { capabilities ->
        val projectDependency = project.dependencies.create(project) as ProjectDependency
        projectDependency.capabilities {
            for (capability in capabilities) {
                requireCapability("${capability.group}:${capability.name}")
            }
        }
        projectDependency
    }

    private fun registerArtifacts(objectFactory: ObjectFactory, providerFactory: ProviderFactory) {
        configuration.outgoing.addArtifacts(providerFactory.provider {
            val layerTasks = LinkedHashMap<String, TaskProvider<OciLayerTask>>()
            getBundleOrPlatformBundles().collectLayerTasks(layerTasks)
            listOf(LazyPublishArtifact(objectFactory).apply {
                file.set(componentTask.flatMap { it.file })
                name.set(project.name)
                classifier.set(componentTask.flatMap { it.classifier })
                extension.set("json")
            }) + layerTasks.map { (_, layerTask) ->
                LazyPublishArtifact(objectFactory).apply {
                    file.set(layerTask.flatMap { it.file })
                    name.set(project.name)
                    classifier.set(layerTask.flatMap { it.classifier })
                    extension.set(layerTask.flatMap { it.extension })
                }
            }
        })
    }

    final override fun getName() = name

    final override fun capabilities(configuration: Action<in OciImageDefinition.Capabilities>) =
        configuration.execute(capabilities)

    final override fun allPlatforms(configuration: Action<in OciImageDefinition.BundleScope>) {
        var bundleScope = allPlatformBundleScope
        if (bundleScope == null) {
            bundleScope = objectFactory.newInstance<BundleScope>(AllPlatformFilter, name, bundles)
            allPlatformBundleScope = bundleScope
        }
        configuration.execute(bundleScope)
    }

    final override fun platformsMatching(
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

    final override fun specificPlatform(platform: Platform) {
        getOrCreatePlatformBundle(platform)
    }

    final override fun specificPlatform(platform: Platform, configuration: Action<in OciImageDefinition.Bundle>) =
        configuration.execute(getOrCreatePlatformBundle(platform))

    private fun getOrCreatePlatformBundle(platform: Platform): Bundle {
        var platformBundles = platformBundles
        if (platformBundles == null) {
            platformBundles = TreeMap()
            this.platformBundles = platformBundles
        }
        var bundle = platformBundles[platform]
        if (bundle == null) {
            bundle = objectFactory.newInstance<Bundle>(name, configuration, Optional.of(platform))
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
            universalBundle = objectFactory.newInstance<Bundle>(name, configuration, Optional.empty<Platform>())
            bundles.add(universalBundle)
            this.universalBundle = universalBundle
        }
        return universalBundle
    }


    abstract class Capabilities @Inject constructor(
        private val configurationPublications: ConfigurationPublications,
        imageDefName: String,
        providerFactory: ProviderFactory,
        project: Project,
    ) : OciImageDefinition.Capabilities {

        final override val set: Provider<Set<Capability>> = providerFactory.provider {
            configurationPublications.capabilities.toSet()
        }

        init {
            if (!imageDefName.isMain()) {
                project.afterEvaluate {
                    if (configurationPublications.capabilities.isEmpty()) {
                        add("$group:${name.concatKebabCase(imageDefName.kebabCase())}:$version")
                    }
                }
            }
        }

        final override fun add(notation: String) = configurationPublications.capability(notation)
    }


    sealed interface BundleOrPlatformBundles {
        fun collectLayerTasks(linkedMap: LinkedHashMap<String, TaskProvider<OciLayerTask>>)
        fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<out OciComponent.BundleOrPlatformBundles>
    }


    abstract class Bundle @Inject constructor(
        imageDefName: String,
        imageConfiguration: Configuration,
        platform: Optional<Platform>,
        objectFactory: ObjectFactory,
        private val projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
    ) : OciImageDefinition.Bundle, BundleOrPlatformBundles {

        val platform: Platform? = platform.orElse(null)
        final override val parentImages = objectFactory.newInstance<ParentImages>(imageConfiguration)
        final override val config = objectFactory.newInstance<OciImageDefinition.Bundle.Config>().apply {
            entryPoint.convention(null)
            arguments.convention(null)
        }
        final override val layers = objectFactory.newInstance<Layers>(imageDefName, platform)

        final override fun parentImages(configuration: Action<in OciImageDefinition.Bundle.ParentImages>) =
            configuration.execute(parentImages)

        final override fun config(configuration: Action<in OciImageDefinition.Bundle.Config>) =
            configuration.execute(config)

        final override fun layers(configuration: Action<in OciImageDefinition.Bundle.Layers>) =
            configuration.execute(layers)

        final override fun collectLayerTasks(linkedMap: LinkedHashMap<String, TaskProvider<OciLayerTask>>) {
            for (layer in layers.list) {
                layer as Layer
                val task = layer.getTask()
                if (task != null) {
                    linkedMap.putIfAbsent(task.name, task)
                }
            }
        }

        final override fun createComponentBundleOrPlatformBundles(providerFactory: ProviderFactory): Provider<OciComponent.Bundle> =
            providerFactory.provider { OciComponentBundleBuilder() }
                .zip(createComponentParentCapabilities(providerFactory), OciComponentBundleBuilder::parentCapabilities)
                .zipAbsentAsNull(config.creationTime, OciComponentBundleBuilder::creationTime)
                .zipAbsentAsNull(config.author, OciComponentBundleBuilder::author)
                .zipAbsentAsNull(config.user, OciComponentBundleBuilder::user)
                .zip(config.ports.orElse(emptySet()), OciComponentBundleBuilder::ports)
                .zip(config.environment.orElse(emptyMap()), OciComponentBundleBuilder::environment)
                .zipAbsentAsNull(createComponentCommand(providerFactory), OciComponentBundleBuilder::command)
                .zip(config.volumes.orElse(emptySet()), OciComponentBundleBuilder::volumes)
                .zipAbsentAsNull(config.workingDirectory, OciComponentBundleBuilder::workingDirectory)
                .zipAbsentAsNull(config.stopSignal, OciComponentBundleBuilder::stopSignal)
                .zip(config.configAnnotations.orElse(emptyMap()), OciComponentBundleBuilder::configAnnotations)
                .zip(
                    config.configDescriptorAnnotations.orElse(emptyMap()),
                    OciComponentBundleBuilder::configDescriptorAnnotations,
                )
                .zip(config.manifestAnnotations.orElse(emptyMap()), OciComponentBundleBuilder::manifestAnnotations)
                .zip(
                    config.manifestDescriptorAnnotations.orElse(emptyMap()),
                    OciComponentBundleBuilder::manifestDescriptorAnnotations,
                )
                .zip(createComponentLayers(providerFactory), OciComponentBundleBuilder::layers)
                .map { it.build() }

        private fun createComponentParentCapabilities(providerFactory: ProviderFactory): Provider<List<Coordinates>> =
            providerFactory.provider {
                val parentCapabilities = mutableListOf<Coordinates>()
                for (dependency in parentImages.set) {
                    val capabilities = dependency.requestedCapabilities
                    if (capabilities.isEmpty()) {
                        parentCapabilities.add(dependency.getDefaultCapability(projectDependencyPublicationResolver))
                    } else {
                        for (capability in capabilities) {
                            parentCapabilities.add(Coordinates(capability.group, capability.name))
                        }
                    }
                }
                parentCapabilities
            }

        private fun createComponentCommand(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Command> =
            providerFactory.provider { OciComponentBundleCommandBuilder() }
                .zipAbsentAsNull(config.entryPoint, OciComponentBundleCommandBuilder::entryPoint)
                .zipAbsentAsNull(config.arguments, OciComponentBundleCommandBuilder::arguments)
                .map { it.build() }

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
            configuration: Configuration,
            dependencyHandler: DependencyHandler,
        ) : OciImageDependenciesImpl<Unit>(configuration, dependencyHandler), OciImageDefinition.Bundle.ParentImages {

            final override fun returnType(dependency: ModuleDependency) = Unit

            final override fun returnType(dependencyProvider: Provider<out ModuleDependency>) = Unit
        }

        abstract class Layers @Inject constructor(
            private val imageDefName: String,
            platform: Optional<Platform>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.Bundle.Layers {

            private val platform: Platform? = platform.orElse(null)
            final override val list = objectFactory.namedDomainObjectList(OciImageDefinition.Bundle.Layer::class)

            final override fun layer(name: String, configuration: Action<in OciImageDefinition.Bundle.Layer>) =
                configuration.execute(layer(name))

            fun layer(name: String): Layer {
                var layer = list.findByName(name) as Layer?
                if (layer == null) {
                    layer = objectFactory.newInstance<Layer>(name, imageDefName, Optional.ofNullable(platform))
                    list.add(layer)
                }
                return layer
            }
        }


        abstract class Layer @Inject constructor(
            private val name: String,
            private val imageDefName: String,
            platform: Optional<Platform>,
            objectFactory: ObjectFactory,
            private val taskContainer: TaskContainer,
            private val projectLayout: ProjectLayout,
        ) : OciImageDefinition.Bundle.Layer {

            private val platform: Platform? = platform.orElse(null)
            final override val metadata = objectFactory.newInstance<OciImageDefinition.Bundle.Layer.Metadata>().apply {
                createdBy.convention("gradle-oci: $name")
            }

            private var task: TaskProvider<OciLayerTask>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            final override fun getName() = name

            final override fun metadata(configuration: Action<in OciImageDefinition.Bundle.Layer.Metadata>) =
                configuration.execute(metadata)

            final override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                val task = task
                if (task == null) {
                    this.task = taskContainer.createLayerTask(
                        imageDefName, name, platform?.toString() ?: "", projectLayout, configuration
                    )
                } else {
                    task {
                        contents(configuration)
                    }
                }
            }

            final override fun contents(task: TaskProvider<OciLayerTask>) {
                externalTask = if (task == this.task) null else task
            }

            fun getTask() = externalTask ?: task

            fun createComponentLayer(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Layer> =
                providerFactory.provider { OciComponentBundleLayerBuilder() }
                    .zipAbsentAsNull(metadata.creationTime, OciComponentBundleLayerBuilder::creationTime)
                    .zipAbsentAsNull(metadata.author, OciComponentBundleLayerBuilder::author)
                    .zipAbsentAsNull(metadata.createdBy, OciComponentBundleLayerBuilder::createdBy)
                    .zipAbsentAsNull(metadata.comment, OciComponentBundleLayerBuilder::comment)
                    .zipAbsentAsNull(
                        createComponentLayerDescriptor(providerFactory),
                        OciComponentBundleLayerBuilder::descriptor,
                    )
                    .map { it.build() }

            private fun createComponentLayerDescriptor(providerFactory: ProviderFactory): Provider<OciComponent.Bundle.Layer.Descriptor> {
                val task = providerFactory.provider { getTask() }.flatMap { it }
                return providerFactory.provider { OciComponentBundleLayerDescriptorBuilder() }
                    .zip(metadata.annotations.orElse(emptyMap()), OciComponentBundleLayerDescriptorBuilder::annotations)
                    .zipAbsentAsNull(task.flatMap { it.mediaType }, OciComponentBundleLayerDescriptorBuilder::mediaType)
                    .zipAbsentAsNull(task.flatMap { it.digest }, OciComponentBundleLayerDescriptorBuilder::digest)
                    .zipAbsentAsNull(task.flatMap { it.size }, OciComponentBundleLayerDescriptorBuilder::size)
                    .zipAbsentAsNull(task.flatMap { it.diffId }, OciComponentBundleLayerDescriptorBuilder::diffId)
                    .map { it.build() }
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
        imageDefName: String,
        bundles: DomainObjectSet<Bundle>,
        objectFactory: ObjectFactory,
    ) : OciImageDefinition.BundleScope {

        private val filteredBundles = when (platformFilter) {
            AllPlatformFilter -> bundles
            else -> bundles.matching { bundle -> platformFilter.matches(bundle.platform) }
        }
        final override val layers = objectFactory.newInstance<Layers>(platformFilter, imageDefName, filteredBundles)

        final override fun parentImages(configuration: Action<in OciImageDefinition.Bundle.ParentImages>) =
            filteredBundles.configureEach { parentImages(configuration) }

        final override fun config(configuration: Action<in OciImageDefinition.Bundle.Config>) =
            filteredBundles.configureEach { config(configuration) }

        final override fun layers(configuration: Action<in OciImageDefinition.BundleScope.Layers>) =
            configuration.execute(layers)

        abstract class Layers @Inject constructor(
            private val platformFilter: PlatformFilter,
            private val imageDefName: String,
            private val bundles: DomainObjectSet<Bundle>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.BundleScope.Layers {

            final override val list = objectFactory.namedDomainObjectList(OciImageDefinition.BundleScope.Layer::class)

            final override fun layer(name: String, configuration: Action<in OciImageDefinition.BundleScope.Layer>) =
                configuration.execute(layer(name))

            fun layer(name: String): Layer {
                var layer = list.findByName(name) as Layer?
                if (layer == null) {
                    layer = objectFactory.newInstance<Layer>(name, platformFilter, imageDefName, bundles)
                    list.add(layer)
                }
                return layer
            }
        }

        abstract class Layer @Inject constructor(
            private val name: String,
            private val platformFilter: PlatformFilter,
            private val imageDefName: String,
            private val bundles: DomainObjectSet<Bundle>,
            private val projectLayout: ProjectLayout,
            private val taskContainer: TaskContainer,
        ) : OciImageDefinition.BundleScope.Layer {

            private var task: TaskProvider<OciLayerTask>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            final override fun getName() = name

            final override fun metadata(configuration: Action<in OciImageDefinition.Bundle.Layer.Metadata>) =
                bundles.configureEach { layers.layer(name).metadata(configuration) }

            final override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                var task = task
                if (task == null) {
                    task = taskContainer.createLayerTask(
                        imageDefName, name, platformFilter.toString(), projectLayout, configuration
                    )
                    this.task = task
                    bundles.configureEach {
                        layers.layer(name).contents(task)
                    }
                } else {
                    task {
                        contents(configuration)
                    }
                }
            }

            final override fun contents(task: TaskProvider<OciLayerTask>) {
                externalTask = if (task == this.task) null else task
                bundles.configureEach {
                    layers.layer(name).contents(task)
                }
            }
        }
    }
}

private fun TaskContainer.createLayerTask(
    imageDefName: String,
    layerName: String,
    platformString: String,
    projectLayout: ProjectLayout,
    configuration: Action<in OciCopySpec>,
) = register<OciLayerTask>(createOciLayerClassifier(imageDefName, layerName).camelCase() + platformString) {
    group = TASK_GROUP_NAME
    description = "Assembles the OCI layer '$layerName' for the $imageDefName image."
    destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
    classifier.set(createOciLayerClassifier(imageDefName, layerName) + platformString)
    contents(configuration)
}
