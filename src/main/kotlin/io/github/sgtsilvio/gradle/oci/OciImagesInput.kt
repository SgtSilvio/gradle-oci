package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciDigestAlgorithm
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.File
import java.io.FileInputStream
import java.util.*

/**
 * @author Silvio Giebl
 */
@NonExtensible
interface OciImagesInput {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val files: ConfigurableFileCollection

    @get:Input
    val rootCapabilities: MapProperty<Coordinates, Set<ResolvableOciImageDependencies.Reference>>

    fun from(dependencies: ResolvableOciImageDependencies) {
        files.setFrom(dependencies.configuration)
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
            val (components, digestToLayer) = findComponents(imagesInput.files.files)
            val componentResolver = OciComponentResolver()
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

    private fun findComponents(files: Collection<File>): Pair<List<OciComponent>, Map<OciDigest, File>> {
        val filesArray = files.toTypedArray()
        val components = mutableListOf<OciComponent>()
        val layers = HashMap<OciDigest, File>()
        var filesIndex = 0
        while (filesIndex < filesArray.size) {
            val componentFile = filesArray[filesIndex++]
            if (componentFile.extension != "json") {
                throw IllegalStateException("expecting oci component json file") // TODO message
            }
            val component = componentFile.readText().decodeAsJsonToOciComponent()
            components += component
            val layerDescriptorsIterator = component.allLayers.mapNotNull { it.descriptor }.iterator()
            while (layerDescriptorsIterator.hasNext()) {
                val layerDescriptor = layerDescriptorsIterator.next()
                if (layerDescriptor.digest !in layers) { // layer file is required as digest has not been seen yet
                    if (filesIndex == filesArray.size) {
                        throw IllegalStateException("missing required layer") // TODO message
                    }
                    val file = filesArray[filesIndex++]
                    if (file.extension == "json") {
                        throw IllegalStateException("missing required layer") // TODO message
                    }
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
                    while (true) {
                        if (!layerDescriptorsIterator.hasNext()) {
                            val lastDepth = leaves.last.depth
                            for (leaf in leaves) {
                                if (leaf.depth < lastDepth) {
                                    leaf.drop()
                                }
                            }
                            break
                        }
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
                                throw IllegalStateException("layer does not match any of the digests") // TODO message
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
                if ((child == null) || (child.depth != (depth + 1))) {
                    child = Node(layerDescriptor, depth + 1)
                    leaves += child
                }
                children[layerDescriptor.digest] = child
                child.parents += this
            }
            return child
        }

        fun drop() {
            if (children.isEmpty()) {
                for (parent in parents) {
                    parent.children.remove(layerDescriptor!!.digest)
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
}

// empty tar diffIds (1024 bytes zeros)
// sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef
// sha512:8efb4f73c5655351c444eb109230c556d39e2c7624e9c11abc9e3fb4b9b9254218cc5085b454a9698d085cfa92198491f07a723be4574adc70617b73eb0b6461
