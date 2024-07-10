package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.OciLayerTask
import io.github.sgtsilvio.gradle.oci.OciMetadataTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.internal.gradle.*
import io.github.sgtsilvio.gradle.oci.internal.string.camelCase
import io.github.sgtsilvio.gradle.oci.internal.string.concatKebabCase
import io.github.sgtsilvio.gradle.oci.internal.string.kebabCase
import io.github.sgtsilvio.gradle.oci.mapping.defaultMappedImageNamespace
import io.github.sgtsilvio.gradle.oci.metadata.*
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
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import org.gradle.util.GradleVersion
import java.util.*
import javax.inject.Inject

internal abstract class OciImageDefinitionImpl @Inject constructor(
    private val name: String,
    private val objectFactory: ObjectFactory,
    providerFactory: ProviderFactory,
    configurationContainer: ConfigurationContainer,
    project: Project,
) : OciImageDefinition {

    final override val imageName: Property<String> =
        objectFactory.property<String>().convention(providerFactory.provider {
            defaultMappedImageNamespace(project.group.toString()) + project.name
        })
    final override val imageTag: Property<String> =
        objectFactory.property<String>().convention(providerFactory.provider {
            project.version.toString().concatKebabCase(name.mainToEmpty().kebabCase())
        })
    val imageReference: Provider<OciImageReference> = imageName.zip(imageTag, ::OciImageReference)
    val configuration = createConfiguration(configurationContainer, name, objectFactory)
    final override val capabilities = objectFactory.newInstance(
        if (GradleVersion.current() >= GradleVersion.version("8.6")) Capabilities::class.java else Capabilities.Legacy::class.java,
        configuration.outgoing,
        name,
    )
    private val bundles = objectFactory.domainObjectSet(Bundle::class)
    private var allPlatformBundleScope: BundleScope? = null
    private var platformBundleScopes: HashMap<PlatformFilter, BundleScope>? = null
    private var universalBundle: UniversalBundle? = null
    private var platformBundles: LinkedHashMap<Platform, PlatformBundle>? = null // linked because it will be iterated
    final override val dependency = project.createDependency().requireCapabilities(capabilities.set)

    init {
        project.afterEvaluate {
            val platformBundles = platformBundles
            if (platformBundles != null) {
                for (bundle in platformBundles.values) {
                    bundle.onAfterEvaluate()
                }
            } else if (universalBundle == null) {
                val bundle = objectFactory.newInstance<UniversalBundle>(this@OciImageDefinitionImpl)
                bundles.add(bundle)
                universalBundle = bundle
            }
        }
    }

    private fun createConfiguration(
        configurationContainer: ConfigurationContainer,
        imageDefName: String,
        objectFactory: ObjectFactory,
    ): Configuration = configurationContainer.create(createOciVariantName(imageDefName)).apply {
        description = "Elements of the '$imageDefName' OCI image."
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes.apply {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            attribute(PLATFORM_ATTRIBUTE, UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE)
//            attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
        }
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
            platformBundles = LinkedHashMap()
            this.platformBundles = platformBundles
            configuration.attributes.attribute(PLATFORM_ATTRIBUTE, MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE)
        }
        var bundle = platformBundles[platform]
        if (bundle == null) {
            bundle = objectFactory.newInstance<PlatformBundle>(this, platform)
            bundles.add(bundle)
            platformBundles[platform] = bundle
            configuration.dependencies.addLater(bundle.dependency)
        }
        return bundle
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

        override fun add(notationProvider: Provider<String>) = configurationPublications.capability(notationProvider)

        abstract class Legacy @Inject constructor(
            configurationPublications: ConfigurationPublications,
            imageDefName: String,
            providerFactory: ProviderFactory,
            project: Project,
        ) : Capabilities(configurationPublications, imageDefName, providerFactory, project) {

            private val lazyNotations = mutableListOf<Provider<String>>()

            init {
                project.afterEvaluate {
                    for (lazyNotation in lazyNotations) {
                        val notation = lazyNotation.orNull
                        if (notation != null) {
                            add(notation)
                        }
                    }
                    lazyNotations.clear()
                }
            }

            final override fun add(notationProvider: Provider<String>) {
                lazyNotations += notationProvider
            }
        }
    }


    abstract class Bundle(
        val imageDefinition: OciImageDefinitionImpl,
        val configuration: Configuration,
        val platform: Platform?,
        objectFactory: ObjectFactory,
        providerFactory: ProviderFactory,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
        project: Project,
    ) : OciImageDefinition.Bundle {

        final override val parentImages = objectFactory.newInstance<ParentImages>(configuration)
        final override val config = objectFactory.newInstance<OciImageDefinition.Bundle.Config>().apply {
            entryPoint.convention(null)
            arguments.convention(null)
        }
        final override val layers =
            objectFactory.newInstance<Layers>(imageDefinition.name, Optional.ofNullable(platform))

        init {
            val metadata = createMetadata(providerFactory)
            val metadataTask = taskContainer.createMetadataTask(imageDefinition.name, platform, metadata, projectLayout)
            configuration.outgoing.addArtifacts(providerFactory.provider {
                listOf(LazyPublishArtifact(objectFactory).apply {
                    file.set(metadataTask.flatMap { it.file })
                    name.set(project.name)
                    classifier.set(metadataTask.flatMap { it.classifier })
                    extension.set("json")
                }) + layers.list.mapNotNull { layer ->
                    (layer as Layer).getTask()?.let { layerTask ->
                        LazyPublishArtifact(objectFactory).apply {
                            file.set(layerTask.flatMap { it.file })
                            name.set(project.name)
                            classifier.set(layerTask.flatMap { it.classifier })
                            extension.set(layerTask.flatMap { it.extension })
                        }
                    }
                }
            })
        }

        private fun createMetadata(providerFactory: ProviderFactory): Provider<OciMetadata> =
            providerFactory.provider { OciMetadataBuilder() }
                .zip(imageDefinition.imageReference, OciMetadataBuilder::imageReference)
                .zipAbsentAsNull(config.creationTime, OciMetadataBuilder::creationTime)
                .zipAbsentAsNull(config.author, OciMetadataBuilder::author)
                .zipAbsentAsNull(config.user, OciMetadataBuilder::user)
                .zip(config.ports.orElse(emptySet()), OciMetadataBuilder::ports)
                .zip(config.environment.orElse(emptyMap()), OciMetadataBuilder::environment)
                .zipAbsentAsNull(config.entryPoint, OciMetadataBuilder::entryPoint)
                .zipAbsentAsNull(config.arguments, OciMetadataBuilder::arguments)
                .zip(config.volumes.orElse(emptySet()), OciMetadataBuilder::volumes)
                .zipAbsentAsNull(config.workingDirectory, OciMetadataBuilder::workingDirectory)
                .zipAbsentAsNull(config.stopSignal, OciMetadataBuilder::stopSignal)
                .zip(config.configAnnotations.orElse(emptyMap()), OciMetadataBuilder::configAnnotations)
                .zip(
                    config.configDescriptorAnnotations.orElse(emptyMap()),
                    OciMetadataBuilder::configDescriptorAnnotations,
                )
                .zip(config.manifestAnnotations.orElse(emptyMap()), OciMetadataBuilder::manifestAnnotations)
                .zip(
                    config.manifestDescriptorAnnotations.orElse(emptyMap()),
                    OciMetadataBuilder::manifestDescriptorAnnotations,
                )
                .zip(imageDefinition.indexAnnotations.orElse(emptyMap()), OciMetadataBuilder::indexAnnotations)
                .zip(createMetadataLayers(providerFactory), OciMetadataBuilder::layers)
                .map { it.build() }

        private fun createMetadataLayers(providerFactory: ProviderFactory): Provider<List<OciMetadata.Layer>> =
            providerFactory.provider {
                var listProvider = providerFactory.provider { listOf<OciMetadata.Layer>() }
                for (layer in layers.list) {
                    layer as Layer
                    listProvider = listProvider.zip(layer.createMetadataLayer(providerFactory)) { list, e -> list + e }
                }
                listProvider
            }.flatMap { it }

        private fun TaskContainer.createMetadataTask(
            imageDefName: String,
            platform: Platform?,
            metadata: Provider<OciMetadata>,
            projectLayout: ProjectLayout,
        ) = register<OciMetadataTask>(
            createOciMetadataClassifier(imageDefName).camelCase() + createPlatformPostfix(platform)
        ) {
            group = TASK_GROUP_NAME
            description = "Assembles the metadata json file of the '$imageDefName' OCI image" + if (platform == null) "." else " for the platform $platform"
            encodedMetadata.set(metadata.map { it.encodeToJsonString() })
            destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
            classifier.set(createOciMetadataClassifier(imageDefName) + createPlatformPostfix(platform))
        }

        final override fun parentImages(configuration: Action<in OciImageDefinition.Bundle.ParentImages>) =
            configuration.execute(parentImages)

        final override fun config(configuration: Action<in OciImageDefinition.Bundle.Config>) =
            configuration.execute(config)

        final override fun layers(configuration: Action<in OciImageDefinition.Bundle.Layers>) =
            configuration.execute(layers)

        abstract class ParentImages @Inject constructor(
            configuration: Configuration,
            dependencyHandler: DependencyHandler,
        ) : OciImageDependenciesImpl<Unit>(configuration, dependencyHandler), OciImageDefinition.Bundle.ParentImages {

            final override fun addInternal(dependency: ModuleDependency) {
                configuration.dependencies.add(dependency)
            }

            final override fun addInternal(dependencyProvider: Provider<out ModuleDependency>) {
                configuration.dependencies.addLater(dependencyProvider)
            }
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
            private var bundleScopeConfigurations: LinkedList<Action<in OciCopySpec>>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            final override fun getName() = name

            final override fun metadata(configuration: Action<in OciImageDefinition.Bundle.Layer.Metadata>) =
                configuration.execute(metadata)

            final override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                var task = task
                val bundleScopeConfigurations = bundleScopeConfigurations
                if ((task == null) || (bundleScopeConfigurations != null)) {
                    task = taskContainer.createLayerTask(imageDefName, name, createPlatformPostfix(platform), projectLayout)
                    this.task = task
                    if (bundleScopeConfigurations != null) {
                        this.bundleScopeConfigurations = null
                        task {
                            for (bundleScopeConfiguration in bundleScopeConfigurations) {
                                contents(bundleScopeConfiguration)
                            }
                        }
                    }
                }
                task.contents(configuration)
            }

            fun contentsFromBundleScope(
                bundleScopeConfiguration: Action<in OciCopySpec>,
                bundleScopeTask: TaskProvider<OciLayerTask>,
            ) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                val task = task
                if ((task == null) || (task == bundleScopeTask)) {
                    this.task = bundleScopeTask
                    var bundleScopeConfigurations = bundleScopeConfigurations
                    if (bundleScopeConfigurations == null) {
                        bundleScopeConfigurations = LinkedList()
                        this.bundleScopeConfigurations = bundleScopeConfigurations
                    }
                    bundleScopeConfigurations += bundleScopeConfiguration
                } else {
                    contents(bundleScopeConfiguration)
                }
            }

            final override fun contents(task: TaskProvider<OciLayerTask>) {
                externalTask = if (task == this.task) null else task
            }

            fun getTask() = externalTask ?: task

            fun createMetadataLayer(providerFactory: ProviderFactory): Provider<OciMetadata.Layer> =
                providerFactory.provider { OciMetadataLayerBuilder() }
                    .zipAbsentAsNull(metadata.creationTime, OciMetadataLayerBuilder::creationTime)
                    .zipAbsentAsNull(metadata.author, OciMetadataLayerBuilder::author)
                    .zipAbsentAsNull(metadata.createdBy, OciMetadataLayerBuilder::createdBy)
                    .zipAbsentAsNull(metadata.comment, OciMetadataLayerBuilder::comment)
                    .zipAbsentAsNull(
                        createMetadataLayerDescriptor(providerFactory),
                        OciMetadataLayerBuilder::descriptor,
                    )
                    .map { it.build() }

            private fun createMetadataLayerDescriptor(providerFactory: ProviderFactory): Provider<OciMetadata.Layer.Descriptor> {
                val task = providerFactory.provider { getTask() }.flatMap { it }
                return providerFactory.provider { OciMetadataLayerDescriptorBuilder() }
                    .zip(metadata.annotations.orElse(emptyMap()), OciMetadataLayerDescriptorBuilder::annotations)
                    .zipAbsentAsNull(task.flatMap { it.mediaType }, OciMetadataLayerDescriptorBuilder::mediaType)
                    .zipAbsentAsNull(task.flatMap { it.digest }, OciMetadataLayerDescriptorBuilder::digest)
                    .zipAbsentAsNull(task.flatMap { it.size }, OciMetadataLayerDescriptorBuilder::size)
                    .zipAbsentAsNull(task.flatMap { it.diffId }, OciMetadataLayerDescriptorBuilder::diffId)
                    .map { it.build() }
            }
        }
    }

    abstract class UniversalBundle @Inject constructor(
        imageDefinition: OciImageDefinitionImpl,
        objectFactory: ObjectFactory,
        providerFactory: ProviderFactory,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
        project: Project,
    ) : Bundle(
        imageDefinition,
        imageDefinition.configuration,
        null,
        objectFactory,
        providerFactory,
        taskContainer,
        projectLayout,
        project,
    )

    abstract class PlatformBundle @Inject constructor(
        imageDefinition: OciImageDefinitionImpl,
        platform: Platform,
        objectFactory: ObjectFactory,
        providerFactory: ProviderFactory,
        configurationContainer: ConfigurationContainer,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
        private val project: Project,
    ) : Bundle(
        imageDefinition,
        configurationContainer.create(createOciVariantInternalName(imageDefinition.name, platform)).apply {
            description = "Elements of the '${imageDefinition.name}' OCI image for the platform $platform."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.apply {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
//                attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
            }
        },
        platform,
        objectFactory,
        providerFactory,
        taskContainer,
        projectLayout,
        project,
    ) {
        val dependency: Provider<ProjectDependency> = project.createDependency()
            .requireCapabilities(providerFactory.provider { configuration.outgoing.capabilities })

        private val externalConfiguration: Configuration = configurationContainer.create(createOciVariantName(imageDefinition.name, platform)).apply {
            description = "Elements of the '${imageDefinition.name}' OCI image for the platform $platform."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.apply {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                attribute(PLATFORM_ATTRIBUTE, platform.toString())
//                attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
            }
            dependencies.addLater(dependency)
        }

        fun onAfterEvaluate() {
            val capabilities = imageDefinition.configuration.outgoing.capabilities
            if (capabilities.isEmpty()) {
                configuration.outgoing.capability("${project.group}:${project.name}${createPlatformPostfix(platform)}:${project.version}")
            } else {
                for (capability in capabilities) {
                    externalConfiguration.outgoing.capability(capability)
                    configuration.outgoing.capability("${capability.group}:${capability.name}${createPlatformPostfix(platform)}:${capability.version}")
                }
            }
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
                    task = taskContainer.createLayerTask(imageDefName, name, platformFilter.toString(), projectLayout)
                    this.task = task
                }
                task.contents(configuration)
                bundles.configureEach {
                    layers.layer(name).contentsFromBundleScope(configuration, task)
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
    platformPostfix: String,
    projectLayout: ProjectLayout,
) = register<OciLayerTask>(createOciLayerClassifier(imageDefName, layerName).camelCase() + platformPostfix) {
    group = TASK_GROUP_NAME
    description = "Assembles the layer '$layerName' of the '$imageDefName' OCI image."
    destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
    classifier.set(createOciLayerClassifier(imageDefName, layerName) + platformPostfix)
}

private fun TaskProvider<OciLayerTask>.contents(configuration: Action<in OciCopySpec>) =
    configure { contents(configuration) }
