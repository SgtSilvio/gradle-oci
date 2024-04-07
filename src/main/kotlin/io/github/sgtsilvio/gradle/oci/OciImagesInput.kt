package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
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

    @get:Internal
    val componentIdentifiers: ListProperty<ComponentIdentifier>

    @get:Input
    val rootCapabilities: MapProperty<Coordinates, Set<ResolvableOciImageDependencies.Reference>>

    fun from(dependencies: ResolvableOciImageDependencies) {
        files.setFrom(dependencies.configuration)
        componentIdentifiers.set(dependencies.configuration.incoming.artifacts.resolvedArtifacts.map { results ->
            val files = HashSet<File>()
            results.mapNotNull { if (files.add(it.file)) it.id.componentIdentifier else null }
        })
        rootCapabilities.set(dependencies.rootCapabilities)
    }
}

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imagesInputs = project.objects.listProperty<OciImagesInput>()

    fun from(dependencies: ResolvableOciImageDependencies) =
        imagesInputs.add(project.objects.newInstance<OciImagesInput>().apply { from(dependencies) })

    @TaskAction
    protected fun run() {
        val imagesInputs: List<OciImagesInput> = imagesInputs.get()
        val resolvedComponentToImageReferences = HashMap<ResolvedOciComponent, HashSet<OciImageReference>>()
        val allDigestToLayer = HashMap<OciDigest, File>()
        for (imagesInput in imagesInputs) {
            val componentWithLayersList = findComponents(imagesInput.files, imagesInput.componentIdentifiers.get())
            val componentResolver = OciComponentResolver()
            for ((component, digestToLayer) in componentWithLayersList) {
                componentResolver.addComponent(component)

                for ((digest, layer) in digestToLayer) {
                    val prevLayer = allDigestToLayer.putIfAbsent(digest, layer)
                    if ((prevLayer != null) && (layer != prevLayer)) {
                        checkDuplicateLayer(digest, prevLayer, layer)
                        logger.warn("the same layer ($digest) should not be provided by multiple components")
                    }
                }
            }
            for ((rootCapability, references) in imagesInput.rootCapabilities.get()) {
                val resolvedComponent = componentResolver.resolve(rootCapability)
                val imageReference = resolvedComponent.component.imageReference
                val imageReferences =
                    references.map { OciImageReference(it.name ?: imageReference.name, it.tag ?: imageReference.tag) }
                resolvedComponentToImageReferences.getOrPut(resolvedComponent) { HashSet() }.addAll(imageReferences)
            }
        }
        run(resolvedComponentToImageReferences, allDigestToLayer)
    }

    protected abstract fun run(
        resolvedComponentToImageReferences: Map<ResolvedOciComponent, Set<OciImageReference>>,
        digestToLayer: Map<OciDigest, File>,
    )

    private fun findComponents(
        files: Iterable<File>,
        componentIdentifiers: Iterable<ComponentIdentifier>,
    ): List<Pair<OciComponent, Map<OciDigest, File>>> {
        val componentWithLayersList = mutableListOf<Pair<OciComponent, Map<OciDigest, File>>>()
        val filesIterator = files.iterator()
        val componentIdentifiersIterator = componentIdentifiers.iterator()
        val artifacts = HashMap<ArtifactKey, File>()
        while (filesIterator.hasNext()) {
            val component = filesIterator.next().readText().decodeAsJsonToOciComponent()
            val componentIdentifier = componentIdentifiersIterator.next()
            val digestToLayer = HashMap<OciDigest, File>()
            for (layer in component.allLayers) {
                layer.descriptor?.let { (_, digest, _, _, classifier, extension) ->
                    val artifactKey = ArtifactKey(componentIdentifier, digest, classifier, extension)
                    if (artifactKey !in artifacts) {
                        check(filesIterator.hasNext()) { "ociFiles are missing layers referenced in components" }
                        val layerFile = filesIterator.next()
                        componentIdentifiersIterator.next()
                        artifacts[artifactKey] = layerFile
                        val prevLayerFile = digestToLayer.putIfAbsent(digest, layerFile)
                        if (prevLayerFile != null) {
                            checkDuplicateLayer(digest, prevLayerFile, layerFile)
                        }
                    }
                }
            }
            componentWithLayersList += Pair(component, digestToLayer)
        }
        return componentWithLayersList
    }

    private fun checkDuplicateLayer(digest: OciDigest, file1: File, file2: File) {
        if (!FileUtils.contentEquals(file1, file2)) {
            throw IllegalStateException("hash collision for digest $digest: expected file contents of $file1 and $file2 to be the same")
        }
    }

    private data class ArtifactKey(
        val componentId: ComponentIdentifier,
        val digest: OciDigest,
        val classifier: String?,
        val extension: String?,
    )
}
