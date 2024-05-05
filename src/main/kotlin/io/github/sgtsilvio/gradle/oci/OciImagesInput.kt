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
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.File
import java.io.Serializable
import java.util.*

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

//interface OciComponentInput {
//
//    @get:InputFile
//    @get:PathSensitive(PathSensitivity.NONE)
//    val componentFile: RegularFileProperty
//
//    @get:InputFiles
//    @get:PathSensitive(PathSensitivity.NONE)
//    val layers: ConfigurableFileCollection
//
//    @get:Input
//    val references: SetProperty<ResolvableOciImageDependencies.Reference>
//}

class OciComponentInput(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val componentFile: File,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val layers: List<File>,
    @get:Input val references: Set<ResolvableOciImageDependencies.Reference>,
)

fun ResolvableOciImageDependencies.components(): Provider<List<OciComponentInput>> {
    return configuration.incoming.artifacts.resolvedArtifacts.zip(rootCapabilities) { results, rootCapabilities ->
//    return configuration.incoming.artifacts.resolvedArtifacts.map { println("MAP");it }.zip(rootCapabilities) { results, rootCapabilities ->
        println("zip " + System.identityHashCode(results) + " " + System.identityHashCode(rootCapabilities))
//        Exception().printStackTrace()
        val files = HashSet<File>()
        val resolvedArtifacts = results.filter { files.add(it.file) }

        val componentInputs = mutableListOf<OciComponentInput>()

        val iterator = resolvedArtifacts.iterator()
        val artifacts = HashMap<ArtifactKey, File>()
        while (iterator.hasNext()) {
            val artifact = iterator.next()
            val componentFile = artifact.file
            val component = componentFile.readText().decodeAsJsonToOciComponent()
            val componentIdentifier = artifact.id.componentIdentifier
            val digestToLayer = HashMap<OciDigest, File>()
            val layers = mutableListOf<File>()
            for (layer in component.allLayers) {
                layer.descriptor?.let { (_, digest, _, _, classifier, extension) ->
                    val artifactKey = ArtifactKey(componentIdentifier, digest, classifier, extension)
                    if (artifactKey !in artifacts) {
                        check(iterator.hasNext()) { "ociFiles are missing layers referenced in components" }
                        val layerFile = iterator.next().file
                        artifacts[artifactKey] = layerFile
                        val prevLayerFile = digestToLayer.putIfAbsent(digest, layerFile)
                        if (prevLayerFile == null) {
                            layers += layerFile
                        } else {
//                            checkDuplicateLayer(digest, prevLayerFile, layerFile)
                        }
                    }
                }
            }
            val references = rootCapabilities[component.capabilities.first().coordinates] ?: emptySet() // TODO first is wrong
            componentInputs += OciComponentInput(componentFile, layers.toList(), references)
        }

        componentInputs
    }
}

private data class ArtifactKey( // TODO name
    val componentId: ComponentIdentifier,
    val digest: OciDigest,
    val classifier: String?,
    val extension: String?,
)

abstract class OciImagesInputTask : DefaultTask(), Serializable {

    @get:Nested
    val imagesInputs = project.objects.listProperty<OciImagesInput>()

    @get:Nested
    val components = project.objects.listProperty<OciComponentInput>()//.apply { finalizeValueOnRead() }

//    private val zzz = object : Serializable {
//        private fun readObject(input: ObjectInputStream) {
//            input.defaultReadObject()
//            components.finalizeValueOnRead()
//        }
//    }

    fun from(dependencies: ResolvableOciImageDependencies) {
        imagesInputs.add(project.objects.newInstance<OciImagesInput>().apply { from(dependencies) })
        components.addAll(dependencies.components())
    }

    @TaskAction
    protected fun run() {
        components.get()
        for (ociComponentInput in components.get()) {
            println("component: " + ociComponentInput.componentFile)
//            if (ociComponentInput.layers.isNotEmpty()) {
//                println("layers:")
//                println(ociComponentInput.layers.joinToString("\n") { " - $it" })
//            }
//            if (ociComponentInput.references.isNotEmpty()) {
//                println("references:")
//                println(ociComponentInput.references.joinToString("\n") { " - $it" })
//            }
//            println()
        }
        val imagesInputs: List<OciImagesInput> = imagesInputs.get()
        val resolvedComponentToImageReferences = HashMap<ResolvedOciComponent, HashSet<OciImageReference>>()
        val allDigestToLayer = HashMap<OciDigest, File>()
        for (imagesInput in imagesInputs) {
            val componentWithLayersList = findComponents(imagesInput.files, imagesInput.componentIdentifiers.get())
            val (c, l) = findComponents(imagesInput.files.files)
            for (component in c) {
                println(component.imageReference)
            }
            for (layer in l) {
                println(layer)
            }
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
                        if (prevLayerFile != null) { // TODO maybe only allow known empty diffIds as duplicate in same component, add check in component builder
                            checkDuplicateLayer(digest, prevLayerFile, layerFile)
                        }
                    }
                }
            }
            componentWithLayersList += Pair(component, digestToLayer)
        }
        return componentWithLayersList
    }

    private fun findComponents(files: Collection<File>): Pair<List<OciComponent>, Map<OciDigest, File>> {
        val filesArray = files.toTypedArray()
        val components = mutableListOf<OciComponent>()
        val layers = HashMap<OciDigest, File>()
        var filesIndex = 0
        while (filesIndex < filesArray.size) {
            val component = filesArray[filesIndex++].readText().decodeAsJsonToOciComponent()
            components += component
            val layerDescriptors = component.allLayers.mapNotNull { it.descriptor }.toList().toTypedArray()
            var layerDescriptorsIndex = 0
            while (layerDescriptorsIndex < layerDescriptors.size) {
                val layerDescriptor = layerDescriptors[layerDescriptorsIndex++]
                val digest = layerDescriptor.digest
                val prevFile = layers[digest]
                if (prevFile == null) { // layer file is required as digest has not been seen yet
                    if (filesIndex == filesArray.size) {
                        throw IllegalStateException() // TODO message
                    }
                    val file = filesArray[filesIndex]
                    if (file.extension == "json") {
                        throw IllegalStateException() // TODO message
                    }
                    filesIndex++
                    layers[digest] = file
                } else { // layer file is optional as digest has already been seen
                    if (filesIndex == filesArray.size) {
                        continue
                    }
                    val file = filesArray[filesIndex]
                    if (file.extension == "json") {
                        continue
                    }
                    val fileLength = file.length()
                    if (layerDescriptor.size != fileLength) {
                        continue
                    }
                    filesIndex++
                    val currentIndices = LinkedList<Int>()
                    currentIndices += layerDescriptorsIndex - 1
                    var nextLayerDescriptorIndex = layerDescriptorsIndex
                    while (nextLayerDescriptorIndex < layerDescriptors.size) {
                        val nextLayerDescriptor = layerDescriptors[nextLayerDescriptorIndex]
                        if ((nextLayerDescriptor.size == fileLength) && (nextLayerDescriptor.digest != digest)) {
                            currentIndices += nextLayerDescriptorIndex
                        }
                        if (nextLayerDescriptor.digest !in layers) {
                            break // found next required
                        }
                        nextLayerDescriptorIndex++
                    }
                    if (currentIndices.size == 1) {
                        checkDuplicateLayer(digest, prevFile, file)
                        continue
                    }
                    TODO()
//                    DigestUtils.digest(digest.algorithm.createMessageDigest(), file)
                }
            }
        }
        return Pair(components, layers)
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
