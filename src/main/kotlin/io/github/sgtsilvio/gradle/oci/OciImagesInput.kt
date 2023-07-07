package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
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
//                .filter { it.resolvedVariant.capabilities.first().group != "io.github.sgtsilvio.gradle.oci.tag" }
                .map { dependencyResult ->
                    val capability = dependencyResult.resolvedVariant.capabilities.first()
                    Coordinates(capability.group, capability.name)
                }
        })
    }
}

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imagesList = project.objects.listProperty<OciImagesInput>()

    fun from(configurationsProvider: Provider<List<Configuration>>) =
        imagesList.addAll(configurationsProvider.map { configurations ->
            configurations.map { configuration ->
                project.objects.newInstance<OciImagesInput>().apply { from(configuration) }
            }
        })

    protected fun findComponents(ociFiles: Iterable<File>): List<OciComponentWithLayers> {
        val componentWithLayersList = mutableListOf<OciComponentWithLayers>()
        val iterator = ociFiles.iterator()
        while (iterator.hasNext()) {
            val component = iterator.next().readText().decodeAsJsonToOciComponent()
            val digestToLayer = hashMapOf<OciDigest, File>()
            for (layer in component.allLayers) {
                layer.descriptor?.let {
                    val digest = it.digest
                    if (digest !in digestToLayer) {
                        check(iterator.hasNext()) { "ociFiles are missing layers referenced in components" }
                        digestToLayer[digest] = iterator.next()
                    }
                }
            }
            componentWithLayersList += OciComponentWithLayers(component, digestToLayer)
        }
        return componentWithLayersList
    }

    protected data class OciComponentWithLayers(val component: OciComponent, val digestToLayer: Map<OciDigest, File>)

    protected fun collectLayers(componentWithLayersList: List<OciComponentWithLayers>): Map<OciDigest, File> {
        val allDigestToLayer = hashMapOf<OciDigest, File>()
        for ((_, digestToLayer) in componentWithLayersList) {
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
        return allDigestToLayer
    }
}

internal val OciComponent.allLayers // TODO deduplicate
    get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
        is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
    }
