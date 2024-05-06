package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciDigestAlgorithm
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
import java.io.FileInputStream
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
//        for (ociComponentInput in components.get()) {
//            println("component: " + ociComponentInput.componentFile)
////            if (ociComponentInput.layers.isNotEmpty()) {
////                println("layers:")
////                println(ociComponentInput.layers.joinToString("\n") { " - $it" })
////            }
////            if (ociComponentInput.references.isNotEmpty()) {
////                println("references:")
////                println(ociComponentInput.references.joinToString("\n") { " - $it" })
////            }
////            println()
//        }
        val imagesInputs: List<OciImagesInput> = imagesInputs.get()
        val resolvedComponentToImageReferences = HashMap<ResolvedOciComponent, HashSet<OciImageReference>>()
        val allDigestToLayer = HashMap<OciDigest, File>()
        for (imagesInput in imagesInputs) {
//            val componentWithLayersList = findComponents(imagesInput.files, imagesInput.componentIdentifiers.get())
            val (components, digestToLayer) = findComponents(imagesInput.files.files)
            val componentResolver = OciComponentResolver()
//            for ((component, digestToLayer) in componentWithLayersList) {
//                componentResolver.addComponent(component)
//
//                for ((digest, layer) in digestToLayer) {
//                    val prevLayer = allDigestToLayer.putIfAbsent(digest, layer)
//                    if ((prevLayer != null) && (layer != prevLayer)) {
//                        checkDuplicateLayer(digest, prevLayer, layer)
//                        logger.warn("the same layer ($digest) should not be provided by multiple components")
//                    }
//                }
//            }
            for (component in components) {
                componentResolver.addComponent(component)
            }
            for ((digest, layer) in digestToLayer) {
                val prevLayer = allDigestToLayer.putIfAbsent(digest, layer)
                if ((prevLayer != null) && (layer != prevLayer)) {
                    checkDuplicateLayer(digest, prevLayer, layer)
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
            val layerDescriptorsIterator = component.allLayers.mapNotNull { it.descriptor }.iterator()
            while (layerDescriptorsIterator.hasNext()) {
                val layerDescriptor = layerDescriptorsIterator.next()
                if (layerDescriptor.digest !in layers) { // layer file is required as digest has not been seen yet
                    if (filesIndex == filesArray.size) {
                        throw IllegalStateException() // TODO message
                    }
                    val file = filesArray[filesIndex]
                    if (file.extension == "json") {
                        throw IllegalStateException() // TODO message
                    }
                    filesIndex++
                    layers[layerDescriptor.digest] = file
                } else { // layer file is optional as digest has already been seen
                    if (filesIndex == filesArray.size) {
                        continue
                    }
                    val file = filesArray[filesIndex]
                    if ((file.extension == "json") || (layerDescriptor.size != file.length())) {
                        continue
                    }
                    var leaves = LinkedList<Node>()
                    val root = Node(null, 0)
                    leaves += root
                    root.addChild(null, layerDescriptor, leaves)
                    val dummyFile = File("")
                    while (layerDescriptorsIterator.hasNext()) {
                        val nextLayerDescriptor = layerDescriptorsIterator.next()
                        if (nextLayerDescriptor.digest in layers) { // optional
                            val newLeaves = LinkedList<Node>()
                            val oldLeavesIterator = leaves.listIterator()
                            var newLeaf: Node? = null
                            for (leaf in leaves) {
                                while (oldLeavesIterator.hasNext()) {
                                    val oldLeaf = oldLeavesIterator.next()
                                    if (oldLeaf.depth <= leaf.depth) {
                                        newLeaves += oldLeaf
                                    } else {
                                        oldLeavesIterator.previous()
                                        break
                                    }
                                }
                                val nextLayerSize = if ((filesIndex + leaf.depth) >= filesArray.size) -1 else {
                                    val nextLayer = filesArray[filesIndex + leaf.depth]
                                    if (nextLayer.extension == "json") -1 else nextLayer.length()
                                }
                                if (nextLayerSize == nextLayerDescriptor.size) {
                                    newLeaf = leaf.addChild(newLeaf, nextLayerDescriptor, newLeaves)
                                }
                            }
                            leaves = newLeaves
                        } else { // required
                            val newLeaves = LinkedList<Node>()
                            var newLeaf: Node? = null
                            for (leaf in leaves) {
                                val nextLayerSize = if ((filesIndex + leaf.depth) >= filesArray.size) -1 else {
                                    val nextLayer = filesArray[filesIndex + leaf.depth]
                                    if (nextLayer.extension == "json") -1 else nextLayer.length()
                                }
                                if (nextLayerSize == nextLayerDescriptor.size) {
                                    newLeaf = leaf.addChild(newLeaf, nextLayerDescriptor, newLeaves)
                                } else {
                                    leaf.drop()
                                }
                            }
                            leaves = newLeaves
                            if (leaves.isEmpty()) {
                                throw IllegalStateException("missing required layer") // TODO message
                            }
                            if (leaves.size == 1) {
                                break
                            }
                            layers[nextLayerDescriptor.digest] = dummyFile
                        }
                    }
                    var node = root
                    while (node.children.isNotEmpty()) {
                        val layer = filesArray[filesIndex++]
                        node = if (node.children.size == 1) {
                            node.children.iterator().next().value
                        } else {
                            val digests =
                                node.children.keys.mapTo(EnumSet.noneOf(OciDigestAlgorithm::class.java)) { it.algorithm }
                                    .map { Pair(it, it.createMessageDigest()) }
                            FileInputStream(layer).use { inputStream ->
                                val BUFFER_SIZE = 4096
                                val buffer = ByteArray(BUFFER_SIZE)
                                var read = inputStream.read(buffer, 0, BUFFER_SIZE)
                                while (read > -1) {
                                    for ((_, messageDigest) in digests) {
                                        messageDigest.update(buffer, 0, read)
                                    }
                                    read = inputStream.read(buffer, 0, BUFFER_SIZE)
                                }
                            }
                            var nextNode: Node? = null
                            for ((digestAlgorithm, messageDigest) in digests) {
                                nextNode = node.children[OciDigest(digestAlgorithm, messageDigest.digest())]
                                if (nextNode != null) {
                                    break
                                }
                            }
                            if (nextNode == null) {
                                throw IllegalStateException() // TODO message
                            }
                            nextNode
                        }
                        val digest = node.layerDescriptor!!.digest
                        val prevLayer = layers[digest]
                        if ((prevLayer == null) || (prevLayer === dummyFile)) {
                            layers[digest] = layer
                        } else {
                            checkDuplicateLayer(digest, prevLayer, layer)
                        }
                    }
                }
            }
        }
        return Pair(components, layers)
    }

    private class Node(val layerDescriptor: OciComponent.Bundle.Layer.Descriptor?, val depth: Int) {
        val parents = LinkedList<Node>()
        val children = LinkedHashMap<OciDigest, Node>()

        fun addChild(node: Node?, layerDescriptor: OciComponent.Bundle.Layer.Descriptor, leaves: LinkedList<Node>): Node? {
            var child = node
            if (layerDescriptor.digest !in children) {
                if ((child == null) || (child.depth == (depth + 1))) {
                    child = Node(layerDescriptor, depth + 1)
                    leaves += child
                }
                children[layerDescriptor.digest] = child
                child.parents += this
            }
            return child
        }

        fun drop() {
            for (parent in parents) {
                parent.children.remove(layerDescriptor!!.digest)
                if (parent.children.isEmpty()) {
                    parent.drop()
                }
            }
        }

        override fun toString(): String {
            return buildString {
                appendLine("[")
                for (child in children) {
                    append(child)
                }
                appendLine("]")
            }
        }
    }

    private fun checkDuplicateLayer(digest: OciDigest, file1: File, file2: File) {
        if (!FileUtils.contentEquals(file1, file2)) {
            throw IllegalStateException("hash collision for digest $digest: expected file contents of $file1 and $file2 to be the same")
        }
//        val EMPTY_LAYER_DIFF_IDS = setOf(
//            "sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef".toOciDigest(),
//            "sha512:8efb4f73c5655351c444eb109230c556d39e2c7624e9c11abc9e3fb4b9b9254218cc5085b454a9698d085cfa92198491f07a723be4574adc70617b73eb0b6461".toOciDigest(),
//        )
//        if (diffId !in EMPTY_LAYER_DIFF_IDS) {
            logger.warn("the same layer ($digest) should not be provided by multiple artifacts ($file1, $file2)")
//        }
    }

    private data class ArtifactKey(
        val componentId: ComponentIdentifier,
        val digest: OciDigest,
        val classifier: String?,
        val extension: String?,
    )
}

// empty tar diffIds (1024 bytes zeros)
// sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef
// sha512:8efb4f73c5655351c444eb109230c556d39e2c7624e9c11abc9e3fb4b9b9254218cc5085b454a9698d085cfa92198491f07a723be4574adc70617b73eb0b6461
