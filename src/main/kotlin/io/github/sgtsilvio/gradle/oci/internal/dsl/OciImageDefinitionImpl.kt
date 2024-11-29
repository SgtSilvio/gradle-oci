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
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.file.ProjectLayout
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
    private val providerFactory: ProviderFactory,
    private val configurationContainer: ConfigurationContainer,
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
    final override val capabilities = objectFactory.listProperty<String>()
    private val variants = objectFactory.domainObjectSet(Variant::class)
    private var allPlatformVariantScope: VariantScope? = null
    private var platformVariantScopes: HashMap<PlatformFilter, VariantScope>? = null
    private var universalVariant: Variant? = null
    private var platformVariants: LinkedHashMap<Platform, Variant>? = null // linked because it will be iterated
    private var indexConfiguration: Configuration? = null
    final override val dependency: Provider<ProjectDependency> = project.createDependency().run {
        capabilities.map { capabilities ->
            capabilities {
                for (capability in capabilities) {
                    requireCapability(capability)
                }
            }
            this
        }
    }

    init {
        if (name != MAIN_NAME) {
            capabilities.convention(providerFactory.provider {
                listOf("${project.group}:${project.name.concatKebabCase(name.kebabCase())}:${project.version}")
            })
        }
        project.afterEvaluate {
            val platformVariants = platformVariants
            if ((platformVariants == null) && (universalVariant == null)) {
                val variant = objectFactory.newInstance<Variant>(this@OciImageDefinitionImpl, Optional.empty<Platform>())
                variants.add(variant)
                universalVariant = variant
            }
            val capabilities = capabilities.get()
            for (variant in variants) {
                val configurationPublications = variant.configuration.outgoing
                for (capability in capabilities) {
                    configurationPublications.capability(capability)
                }
            }
            indexConfiguration?.let { indexConfiguration ->
                val configurationPublications = indexConfiguration.outgoing
                for (capability in capabilities) {
                    configurationPublications.capability(capability)
                }
            }
        }
    }

    final override fun getName() = name

    final override fun allPlatforms(configuration: Action<in OciImageDefinition.VariantScope>) {
        var variantScope = allPlatformVariantScope
        if (variantScope == null) {
            variantScope = objectFactory.newInstance<VariantScope>(AllPlatformFilter, name, variants)
            allPlatformVariantScope = variantScope
        }
        configuration.execute(variantScope)
    }

    final override fun platformsMatching(
        platformFilter: PlatformFilter,
        configuration: Action<in OciImageDefinition.VariantScope>,
    ) {
        if (platformFilter == AllPlatformFilter) {
            return allPlatforms(configuration)
        }
        var variantScopes = platformVariantScopes
        if (variantScopes == null) {
            variantScopes = HashMap(4)
            platformVariantScopes = variantScopes
        }
        var variantScope = variantScopes[platformFilter]
        if (variantScope == null) {
            variantScope = objectFactory.newInstance<VariantScope>(platformFilter, name, variants)
            variantScopes[platformFilter] = variantScope
        }
        configuration.execute(variantScope)
    }

    final override fun specificPlatform(platform: Platform) {
        getOrCreatePlatformVariant(platform)
    }

    final override fun specificPlatform(platform: Platform, configuration: Action<in OciImageDefinition.Variant>) =
        configuration.execute(getOrCreatePlatformVariant(platform))

    private fun getOrCreatePlatformVariant(platform: Platform): Variant {
        var platformVariants = platformVariants
        if (platformVariants == null) {
            platformVariants = LinkedHashMap()
            this.platformVariants = platformVariants
        }
        var variant = platformVariants[platform]
        if (variant == null) {
            variant = objectFactory.newInstance<Variant>(this, Optional.of(platform))
            platformVariants[platform] = variant
            variants.add(variant)
        }
        if ((indexConfiguration == null) && (platformVariants.size > 1)) {
            createIndexConfiguration(platformVariants)
        }
        return variant
    }

    private fun createIndexConfiguration(platformVariants: Map<Platform, Variant>) {
        val indexConfiguration = configurationContainer.create(createOciIndexVariantName(name)).apply {
            description = "Elements of the '$name' OCI image index."
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.apply {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_INDEX_DISTRIBUTION_TYPE)
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                attributeProvider(PLATFORM_ATTRIBUTE, providerFactory.provider {
                    platformVariants.keys.mapTo(TreeSet()) { it.toString() }.joinToString(";")
                })
//                attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
            }
        }
        this.indexConfiguration = indexConfiguration
        variants.all {
            val variantDependencies = dependencies.runtime
            var dependenciesProvider: Provider<out Collection<ModuleDependency>> = variantDependencies.dependencies
            val variantPlatform = platform
            if (variantPlatform != null) {
                dependenciesProvider = dependenciesProvider.map { dependencies ->
                    dependencies.map { dependency ->
                        dependency.copy().attribute(OCI_IMAGE_INDEX_PLATFORM_ATTRIBUTE, variantPlatform.toString())
                    }
                }
            }
            indexConfiguration.dependencies.addAllLater(dependenciesProvider)
            indexConfiguration.dependencyConstraints.addAllLater(variantDependencies.dependencyConstraints)
        }
    }


    abstract class Variant @Inject constructor(
        private val imageDefinition: OciImageDefinitionImpl,
        platformOptional: Optional<Platform>,
        objectFactory: ObjectFactory,
        providerFactory: ProviderFactory,
        configurationContainer: ConfigurationContainer,
        taskContainer: TaskContainer,
        projectLayout: ProjectLayout,
        project: Project,
    ) : OciImageDefinition.Variant {

        val platform: Platform? = platformOptional.orElse(null)

        val configuration: Configuration = configurationContainer.create(createOciVariantName(imageDefinition.name, platform)).apply {
            description = buildString {
                append("Elements of the '${imageDefinition.name}' OCI image")
                if (platform != null) {
                    append(" for the platform $platform")
                }
                append('.')
            }
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes.apply {
                attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
                attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
                attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
                if (platform != null) {
                    attribute(PLATFORM_ATTRIBUTE, platform.toString())
                }
//                attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named("release"))
            }
        }

        final override val dependencies = objectFactory.newInstance<Dependencies>()
        final override val config = objectFactory.newInstance<OciImageDefinition.Variant.Config>().apply {
            entryPoint.convention(null)
            arguments.convention(null)
        }
        final override val layers =
            objectFactory.newInstance<Layers>(imageDefinition.name, Optional.ofNullable(platform))

        init {
            configuration.dependencies.addAllLater(dependencies.runtime.dependencies)
            configuration.dependencyConstraints.addAllLater(dependencies.runtime.dependencyConstraints)
            val metadata = createMetadata(objectFactory, providerFactory)
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

        private fun createMetadata(
            objectFactory: ObjectFactory,
            providerFactory: ProviderFactory,
        ): Provider<OciMetadata> = providerFactory.provider { OciMetadataBuilder() }
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
            .zip(createLayerMetadataList(objectFactory, providerFactory), OciMetadataBuilder::layers)
            .map { it.build() }

        private fun createLayerMetadataList(
            objectFactory: ObjectFactory,
            providerFactory: ProviderFactory,
        ): Provider<List<OciLayerMetadata>> = providerFactory.provider { layers.list }.flatMap { layers ->
            val listProperty = objectFactory.listProperty<OciLayerMetadata>()
            for (layer in layers) {
                layer as Layer
                listProperty.add(layer.createLayerMetadata(providerFactory))
            }
            listProperty
        }

        final override fun dependencies(configuration: Action<in OciImageDefinition.Variant.Dependencies>) =
            configuration.execute(dependencies)

        final override fun config(configuration: Action<in OciImageDefinition.Variant.Config>) =
            configuration.execute(config)

        final override fun layers(configuration: Action<in OciImageDefinition.Variant.Layers>) =
            configuration.execute(layers)

        abstract class Dependencies @Inject constructor(
            objectFactory: ObjectFactory,
            dependencyConstraintHandler: DependencyConstraintHandler,
        ) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDefinition.Variant.Dependencies {

            final override val runtime = objectFactory.newInstance<OciImageDependencyCollectorImpl.Default>()
        }

        abstract class Layers @Inject constructor(
            private val imageDefName: String,
            platform: Optional<Platform>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.Variant.Layers {

            private val platform: Platform? = platform.orElse(null)
            final override val list = objectFactory.namedDomainObjectList(OciImageDefinition.Variant.Layer::class)

            final override fun layer(name: String, configuration: Action<in OciImageDefinition.Variant.Layer>) =
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
        ) : OciImageDefinition.Variant.Layer {

            private val platform: Platform? = platform.orElse(null)
            final override val metadata = objectFactory.newInstance<OciImageDefinition.Variant.Layer.Metadata>().apply {
                createdBy.convention("gradle-oci: $name")
            }

            private var task: TaskProvider<OciLayerTask>? = null
            private var variantScopeConfigurations: LinkedList<Action<in OciCopySpec>>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            final override fun getName() = name

            final override fun metadata(configuration: Action<in OciImageDefinition.Variant.Layer.Metadata>) =
                configuration.execute(metadata)

            final override fun contents(configuration: Action<in OciCopySpec>) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                var task = task
                val variantScopeConfigurations = variantScopeConfigurations
                if ((task == null) || (variantScopeConfigurations != null)) {
                    task = taskContainer.createLayerTask(imageDefName, name, createPlatformPostfix(platform), projectLayout)
                    this.task = task
                    if (variantScopeConfigurations != null) {
                        this.variantScopeConfigurations = null
                        task {
                            for (variantScopeConfiguration in variantScopeConfigurations) {
                                contents(variantScopeConfiguration)
                            }
                        }
                    }
                }
                task.contents(configuration)
            }

            fun contentsFromVariantScope(
                variantScopeConfiguration: Action<in OciCopySpec>,
                variantScopeTask: TaskProvider<OciLayerTask>,
            ) {
                if (externalTask != null) {
                    throw IllegalStateException("'contents {}' must not be called if 'contents(task)' was called")
                }
                val task = task
                if ((task == null) || (task == variantScopeTask)) {
                    this.task = variantScopeTask
                    var variantScopeConfigurations = variantScopeConfigurations
                    if (variantScopeConfigurations == null) {
                        variantScopeConfigurations = LinkedList()
                        this.variantScopeConfigurations = variantScopeConfigurations
                    }
                    variantScopeConfigurations += variantScopeConfiguration
                } else {
                    contents(variantScopeConfiguration)
                }
            }

            final override fun contents(task: TaskProvider<OciLayerTask>) {
                externalTask = if (task == this.task) null else task
            }

            fun getTask() = externalTask ?: task

            fun createLayerMetadata(providerFactory: ProviderFactory): Provider<OciLayerMetadata> =
                providerFactory.provider { OciLayerMetadataBuilder() }
                    .zipAbsentAsNull(metadata.creationTime, OciLayerMetadataBuilder::creationTime)
                    .zipAbsentAsNull(metadata.author, OciLayerMetadataBuilder::author)
                    .zipAbsentAsNull(metadata.createdBy, OciLayerMetadataBuilder::createdBy)
                    .zipAbsentAsNull(metadata.comment, OciLayerMetadataBuilder::comment)
                    .zipAbsentAsNull(createLayerDescriptor(providerFactory), OciLayerMetadataBuilder::descriptor)
                    .map { it.build() }

            private fun createLayerDescriptor(providerFactory: ProviderFactory): Provider<OciLayerDescriptor> {
                val task = providerFactory.provider { getTask() }.flatMap { it }
                return providerFactory.provider { OciLayerDescriptorBuilder() }
                    .zip(metadata.annotations.orElse(emptyMap()), OciLayerDescriptorBuilder::annotations)
                    .zipAbsentAsNull(task.flatMap { it.mediaType }, OciLayerDescriptorBuilder::mediaType)
                    .zipAbsentAsNull(task.flatMap { it.digest }, OciLayerDescriptorBuilder::digest)
                    .zipAbsentAsNull(task.flatMap { it.size }, OciLayerDescriptorBuilder::size)
                    .zipAbsentAsNull(task.flatMap { it.diffId }, OciLayerDescriptorBuilder::diffId)
                    .map { it.build() }
            }
        }
    }


    abstract class VariantScope @Inject constructor(
        private val platformFilter: PlatformFilter,
        imageDefName: String,
        variants: DomainObjectSet<Variant>,
        objectFactory: ObjectFactory,
    ) : OciImageDefinition.VariantScope {

        private val filteredVariants = when (platformFilter) {
            AllPlatformFilter -> variants
            else -> variants.matching { variant -> platformFilter.matches(variant.platform) }
        }
        final override val layers = objectFactory.newInstance<Layers>(platformFilter, imageDefName, filteredVariants)

        final override fun dependencies(configuration: Action<in OciImageDefinition.Variant.Dependencies>) =
            filteredVariants.configureEach { dependencies(configuration) }

        final override fun config(configuration: Action<in OciImageDefinition.Variant.Config>) =
            filteredVariants.configureEach { config(configuration) }

        final override fun layers(configuration: Action<in OciImageDefinition.VariantScope.Layers>) =
            configuration.execute(layers)

        abstract class Layers @Inject constructor(
            private val platformFilter: PlatformFilter,
            private val imageDefName: String,
            private val variants: DomainObjectSet<Variant>,
            private val objectFactory: ObjectFactory,
        ) : OciImageDefinition.VariantScope.Layers {

            final override val list = objectFactory.namedDomainObjectList(OciImageDefinition.VariantScope.Layer::class)

            final override fun layer(name: String, configuration: Action<in OciImageDefinition.VariantScope.Layer>) =
                configuration.execute(layer(name))

            fun layer(name: String): Layer {
                var layer = list.findByName(name) as Layer?
                if (layer == null) {
                    layer = objectFactory.newInstance<Layer>(name, platformFilter, imageDefName, variants)
                    list.add(layer)
                }
                return layer
            }
        }

        abstract class Layer @Inject constructor(
            private val name: String,
            private val platformFilter: PlatformFilter,
            private val imageDefName: String,
            private val variants: DomainObjectSet<Variant>,
            private val projectLayout: ProjectLayout,
            private val taskContainer: TaskContainer,
        ) : OciImageDefinition.VariantScope.Layer {

            private var task: TaskProvider<OciLayerTask>? = null
            private var externalTask: TaskProvider<OciLayerTask>? = null

            final override fun getName() = name

            final override fun metadata(configuration: Action<in OciImageDefinition.Variant.Layer.Metadata>) =
                variants.configureEach { layers.layer(name).metadata(configuration) }

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
                variants.configureEach {
                    layers.layer(name).contentsFromVariantScope(configuration, task)
                }
            }

            final override fun contents(task: TaskProvider<OciLayerTask>) {
                externalTask = if (task == this.task) null else task
                variants.configureEach {
                    layers.layer(name).contents(task)
                }
            }
        }
    }
}

private fun TaskContainer.createMetadataTask(
    imageDefName: String,
    platform: Platform?,
    metadata: Provider<OciMetadata>,
    projectLayout: ProjectLayout,
) = register<OciMetadataTask>(createOciMetadataClassifier(imageDefName).camelCase() + createPlatformPostfix(platform)) {
    group = TASK_GROUP_NAME
    description = buildString {
        append("Assembles the metadata json file of the '$imageDefName' OCI image")
        if (platform != null) {
            append(" for the platform $platform")
        }
        append('.')
    }
    encodedMetadata.set(metadata.map { it.encodeToJsonString() })
    destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
    classifier.set(createOciMetadataClassifier(imageDefName) + createPlatformPostfix(platform))
}

private fun TaskContainer.createLayerTask(
    imageDefName: String,
    layerName: String,
    platformPostfix: String,
    projectLayout: ProjectLayout,
) = register<OciLayerTask>(createOciLayerClassifier(imageDefName, layerName).camelCase() + platformPostfix) {
    group = TASK_GROUP_NAME
    description = "Assembles the '$layerName' layer of the '$imageDefName' OCI image."
    destinationDirectory.set(projectLayout.buildDirectory.dir("oci/images/$imageDefName"))
    classifier.set(createOciLayerClassifier(imageDefName, layerName) + platformPostfix)
}

private fun TaskProvider<OciLayerTask>.contents(configuration: Action<in OciCopySpec>) =
    configure { contents(configuration) }
