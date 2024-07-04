package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
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

    @get:Input
    val rootCapabilities: MapProperty<Coordinates, Set<OciImageReferenceSpec>>

    fun from(dependencies: ResolvableOciImageDependencies) {
        files.setFrom(dependencies.configuration)
        rootCapabilities.set(dependencies.rootCapabilities)
    }
}

class OciImagesInput2(
    @get:Nested val variantInputs: List<OciVariantInput>,
    @get:Nested val imageInputs: List<OciImageInput>,
)

class OciVariantInput(
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val files: List<File>,
)

class OciImageInput(
    @get:Input val platform: Platform,
    @get:Input val variantIndices: List<Int>,
    @get:Input val referenceSpecs: Set<OciImageReferenceSpec>,
)

data class OciImageReferenceSpec(val name: String?, val tag: String?) : Serializable

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imagesInputs = project.objects.listProperty<OciImagesInput>()

    fun from(dependencies: ResolvableOciImageDependencies) =
        imagesInputs.add(project.objects.newInstance<OciImagesInput>().apply { from(dependencies) })

    @TaskAction
    protected fun run() {
        val imagesInputs: List<OciImagesInput> = imagesInputs.get()
        val resolvedComponentToImageReferences = HashMap<ResolvedOciComponent, HashSet<OciImageReference>>()
        val digestToLayer = HashMap<OciDigest, File>()
        for (imagesInput in imagesInputs) {
            val (components, layers) = findComponentsAndLayers(imagesInput.files.files.toTypedArray())
            val componentResolver = OciComponentResolver()
            for (component in components) {
                componentResolver.addComponent(component)
            }
            for ((layerDescriptor, layer) in layers) {
                val prevLayer = digestToLayer.putIfAbsent(layerDescriptor.digest, layer)
                if (prevLayer != null) {
                    checkDuplicateLayer(layerDescriptor, prevLayer, layer)
                }
            }
            for ((rootCapability, referenceSpecs) in imagesInput.rootCapabilities.get()) {
                val resolvedComponent = componentResolver.resolve(rootCapability)
                val imageReference = resolvedComponent.component.imageReference
                val imageReferences = referenceSpecs.map {
                    OciImageReference(it.name ?: imageReference.name, it.tag ?: imageReference.tag)
                }
                resolvedComponentToImageReferences.getOrPut(resolvedComponent) { HashSet() }.addAll(imageReferences)
            }
        }
        run(resolvedComponentToImageReferences, digestToLayer)
    }

    protected abstract fun run(
        resolvedComponentToImageReferences: Map<ResolvedOciComponent, Set<OciImageReference>>,
        digestToLayer: Map<OciDigest, File>,
    )

    private fun findComponentsAndLayers(files: Array<File>): Pair<List<OciComponent>, List<Pair<OciComponent.Bundle.Layer.Descriptor, File>>> {
        val components = mutableListOf<OciComponent>()
        val layers = mutableListOf<Pair<OciComponent.Bundle.Layer.Descriptor, File>>()
        val layerDigests = HashSet<OciDigest>()
        var filesIndex = 0
        while (filesIndex < files.size) {
            val componentFile = files[filesIndex++]
            if (componentFile.extension != "json") {
                throw IllegalStateException("expected oci component json file, but got $componentFile")
            }
            val component = componentFile.readText().decodeAsJsonToOciComponent()
            components += component
            val layerDescriptorsIterator = component.allLayerDescriptors.iterator()
            while (layerDescriptorsIterator.hasNext()) {
                val layerDescriptor = layerDescriptorsIterator.next()
                if (layerDigests.add(layerDescriptor.digest)) { // layer file is required as digest has not been seen yet
                    val layer = getLayer(files, filesIndex++)
                        ?: throw IllegalStateException("missing layer for digest ${layerDescriptor.digest}")
                    layers += Pair(layerDescriptor, layer)
                } else { // layer file is optional as digest has already been seen
                    if (getLayer(files, filesIndex)?.length() != layerDescriptor.size) {
                        continue
                    }
                    var leaves = LinkedList<Node>()
                    val root = Node()
                    leaves += root
                    root.addChild(null, layerDescriptor, leaves)
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
                        if (layerDigests.add(nextLayerDescriptor.digest)) { // required
                            val newLeaves = LinkedList<Node>()
                            var newLeaf: Node? = null
                            for (leaf in leaves) {
                                if (getLayer(files, filesIndex + leaf.depth)?.length() == nextLayerDescriptor.size) {
                                    newLeaf = leaf.addChild(newLeaf, nextLayerDescriptor, newLeaves)
                                } else {
                                    leaf.drop()
                                }
                            }
                            leaves = newLeaves
                            if (leaves.isEmpty()) {
                                throw IllegalStateException("missing layer for digest ${nextLayerDescriptor.digest}")
                            }
                            if (leaves.size == 1) {
                                break
                            }
                        } else { // optional
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
                                if (getLayer(files, filesIndex + leaf.depth)?.length() == nextLayerDescriptor.size) {
                                    newLeaf = leaf.addChild(newLeaf, nextLayerDescriptor, newLeaves)
                                }
                            }
                            leaves = newLeaves
                        }
                    }
                    var node = root
                    while (node.children.isNotEmpty()) {
                        val layer = files[filesIndex++]
                        node = if (node.children.size == 1) {
                            node.children.values.iterator().next()
                        } else {
                            val digests =
                                layer.calculateOciDigests(node.children.keys.mapTo(EnumSet.noneOf(OciDigestAlgorithm::class.java)) { it.algorithm })
                            digests.firstNotNullOfOrNull { node.children[it] }
                                ?: throw IllegalStateException("expected layer ($layer) to match any of the digests ${node.children.keys}, but calculated the digests $digests")
                        }
                        layers += Pair(node.layerDescriptor!!, layer)
                    }
                }
            }
        }
        return Pair(components, layers)
    }

    private fun getLayer(files: Array<File>, index: Int) = if (index >= files.size) null else {
        val file = files[index]
        if (file.extension == "json") null else file
    }

    private class Node private constructor(
        val layerDescriptor: OciComponent.Bundle.Layer.Descriptor?,
        val depth: Int,
    ) {
        val parents = LinkedList<Node>()
        val children = LinkedHashMap<OciDigest, Node>()

        constructor() : this(null, 0)

        fun addChild(
            node: Node?,
            layerDescriptor: OciComponent.Bundle.Layer.Descriptor,
            leaves: LinkedList<Node>,
        ): Node? {
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
    }

    private fun checkDuplicateLayer(layerDescriptor: OciComponent.Bundle.Layer.Descriptor, file1: File, file2: File) {
        if (file1 != file2) {
            if (!FileUtils.contentEquals(file1, file2)) {
                throw IllegalStateException("hash collision for digest ${layerDescriptor.digest}: expected file contents of $file1 and $file2 to be the same")
            }
            if (layerDescriptor.diffId !in EMPTY_LAYER_DIFF_IDS) {
                logger.warn("the same layer (${layerDescriptor.digest}) should not be provided by multiple artifacts ($file1, $file2)")
            }
        }
    }
}

// empty tar = 1024 bytes zeros
private val EMPTY_LAYER_DIFF_IDS = setOf(
    "sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef".toOciDigest(),
    "sha512:8efb4f73c5655351c444eb109230c556d39e2c7624e9c11abc9e3fb4b9b9254218cc5085b454a9698d085cfa92198491f07a723be4574adc70617b73eb0b6461".toOciDigest(),
)
