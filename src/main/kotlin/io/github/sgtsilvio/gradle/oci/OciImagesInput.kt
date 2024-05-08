package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.*
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciDigestAlgorithm
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.gradle.oci.metadata.calculateOciDigests
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import java.io.File
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
        val digestToLayer = HashMap<OciDigest, File>()
        for (imagesInput in imagesInputs) {
            val (components, layers) = findComponentsAndLayers(imagesInput.files.files.toTypedArray())
            val componentResolver = OciComponentResolver()
            for (component in components) {
                componentResolver.addComponent(component)
            }
            for ((layerDescriptor, layer) in layers) {
                val prevLayer = digestToLayer.putIfAbsent(layerDescriptor.digest, layer)
                if ((prevLayer != null) && (prevLayer != layer) && !FileUtils.contentEquals(prevLayer, layer)) {
                    throw IllegalStateException("hash collision for digest ${layerDescriptor.digest}: expected file contents of $prevLayer and $layer to be the same")
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
            val layerDescriptorsIterator = component.allLayers.mapNotNull { it.descriptor }.iterator()
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
}
