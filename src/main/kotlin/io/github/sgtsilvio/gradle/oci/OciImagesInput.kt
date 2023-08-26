package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.mapping.OciImageReference
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.File

/**
 * @author Silvio Giebl
 */
@NonExtensible
interface OciImagesInput {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val files: ConfigurableFileCollection

    @get:Input
    val rootCapabilities: SetProperty<Coordinates>

    fun from(configuration: Configuration) {
        files.from(configuration)
        val configurationName = configuration.name
        rootCapabilities.set(configuration.incoming.resolutionResult.rootComponent.map { rootComponent ->
            val rootVariant = rootComponent.variants.find { it.displayName == configurationName }!!
            rootComponent.getDependenciesForVariant(rootVariant)
                .filter { !it.isConstraint }
                .filterIsInstance<ResolvedDependencyResult>() // ignore unresolved, rely on resolution of files
                .filter { it.resolvedVariant.capabilities.first().group != "io.github.sgtsilvio.gradle.oci.tag" }
                .map { dependencyResult ->
                    val capability = dependencyResult.resolvedVariant.capabilities.first()
                    Coordinates(capability.group, capability.name)
                }
        })
    }
}

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imagesInputs = project.objects.listProperty<OciImagesInput>()

    fun from(configurationsProvider: Provider<List<Configuration>>) =
        imagesInputs.addAll(configurationsProvider.map { configurations ->
            configurations.map { configuration ->
                project.objects.newInstance<OciImagesInput>().apply { from(configuration) }
            }
        })

    @TaskAction
    protected fun run() {
        val imagesInputs: List<OciImagesInput> = imagesInputs.get()
        val resolvedComponentToImageReferences = hashMapOf<ResolvedOciComponent, Set<OciImageReference>>()
        val allDigestToLayer = hashMapOf<OciDigest, File>()
        for (imagesInput in imagesInputs) {
            val (componentWithLayersList, tagComponents) = findComponents(imagesInput.files)
            val componentResolver = OciComponentResolver()
            for ((component, digestToLayer) in componentWithLayersList) {
                componentResolver.addComponent(component)

                for ((digest, layer) in digestToLayer) {
                    val prevLayer = allDigestToLayer.putIfAbsent(digest, layer)
                    if ((prevLayer != null) && (layer != prevLayer)) {
                        if (FileUtils.contentEquals(prevLayer, layer)) {
                            logger.warn("the same layer ($digest) should not be provided by multiple components")
                        } else {
                            throw IllegalStateException("hash collision for digest $digest: expected file contents of $prevLayer and $layer to be the same")
                        }
                    }
                }
            }
            for (rootCapability in imagesInput.rootCapabilities.get()) {
                val resolvedComponent = componentResolver.resolve(rootCapability)
                resolvedComponentToImageReferences[resolvedComponent] =
                    setOf(resolvedComponent.component.imageReference)
            }
            for ((imageReference, parentCapability) in tagComponents) {
                resolvedComponentToImageReferences.merge(
                    componentResolver.resolve(parentCapability),
                    setOf(imageReference),
                ) { a, b -> a + b }
            }
        }
        run(resolvedComponentToImageReferences, allDigestToLayer)
    }

    protected abstract fun run(
        resolvedComponentToImageReferences: Map<ResolvedOciComponent, Set<OciImageReference>>,
        digestToLayer: Map<OciDigest, File>,
    )

    private fun findComponents(ociFiles: Iterable<File>): FindComponentsResult {
        val componentWithLayersList = mutableListOf<Pair<OciComponent, Map<OciDigest, File>>>()
        val tagComponents = mutableListOf<OciTagComponent>()
        val iterator = ociFiles.iterator()
        while (iterator.hasNext()) {
            val component = iterator.next().readText().decodeAsJsonToOciComponent()
            val tagComponent = component.asTagOrNull()
            if (tagComponent == null) {
                val digestToLayer = hashMapOf<OciDigest, File>()
                for (layer in component.allLayers) {
                    layer.descriptor?.let { (_, digest) ->
                        if (digest !in digestToLayer) {
                            check(iterator.hasNext()) { "ociFiles are missing layers referenced in components" }
                            digestToLayer[digest] = iterator.next()
                        }
                    }
                }
                componentWithLayersList += Pair(component, digestToLayer)
            } else {
                tagComponents += tagComponent
            }
        }
        return FindComponentsResult(componentWithLayersList, tagComponents)
    }

    private data class FindComponentsResult(
        val componentWithLayersList: List<Pair<OciComponent, Map<OciDigest, File>>>,
        val tagComponents: List<OciTagComponent>,
    )
}

internal fun OciComponent.asTagOrNull(): OciTagComponent? {
    if ((capabilities.size != 1) || (capabilities.first().group != "io.github.sgtsilvio.gradle.oci.tag")) {
//    if (!capabilities.isEmpty()) {
        return null
    }
    val bundle = bundleOrPlatformBundles as? OciComponent.Bundle
        ?: throw IllegalStateException("tag component must only have 1 bundle ($this)")
    val parentCapabilities = bundle.parentCapabilities
    check(parentCapabilities.size == 1) { "tag component must have exactly 1 parent capability" }
    check(bundle.creationTime == null) { "tag component must not set creationTime" }
    check(bundle.author == null) { "tag component must not set author" }
    check(bundle.user == null) { "tag component must not set user" }
    check(bundle.ports.isEmpty()) { "tag component must not set ports" }
    check(bundle.environment.isEmpty()) { "tag component must not set environment" }
    check(bundle.command == null) { "tag component must not set command" }
    check(bundle.volumes.isEmpty()) { "tag component must not set volumes" }
    check(bundle.workingDirectory == null) { "tag component must not set workingDirectory" }
    check(bundle.stopSignal == null) { "tag component must not set stopSignal" }
    check(bundle.configAnnotations.isEmpty()) { "tag component must not set configAnnotations" }
    check(bundle.configDescriptorAnnotations.isEmpty()) { "tag component must not set configDescriptorAnnotations" }
    check(bundle.manifestAnnotations.isEmpty()) { "tag component must not set manifestAnnotations" }
    check(bundle.manifestDescriptorAnnotations.isEmpty()) { "tag component must not set manifestDescriptorAnnotations" }
    check(bundle.layers.isEmpty()) { "tag component must not set layers" }
    check(indexAnnotations.isEmpty()) { "tag component must not set indexAnnotations" }
    return OciTagComponent(imageReference, parentCapabilities[0])
}

internal data class OciTagComponent(val imageReference: OciImageReference, val parentCapability: Coordinates)

internal val OciComponent.allLayers // TODO deduplicate
    get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
        is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
    }
