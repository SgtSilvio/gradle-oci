package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.setProperty
import java.io.File
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
data class OciVariantInput(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val metadataFile: File,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val layerFiles: List<File>,
)

data class OciImageInput(
    @get:Input val platform: Platform,
    @get:Nested val variants: List<OciVariantInput>, // TODO document must not be empty
    @get:Input val referenceSpecs: Set<OciImageReferenceSpec>,
)

data class OciImageReferenceSpec(val name: String?, val tag: String?) : Serializable {
    override fun toString() = (name ?: "") + ":" + (tag ?: "")
}

internal fun String.toOciImageReferenceSpec(): OciImageReferenceSpec {
    val parts = split(':')
    if (parts.size != 2) {
        throw IllegalArgumentException("'$this' must contain exactly one ':' character")
    }
    return OciImageReferenceSpec(parts[0].takeIf { it.isNotEmpty() }, parts[1].takeIf { it.isNotEmpty() })
}

// TODO factory method for OciImageReferenceSpec that returns DEFAULT_OCI_REFERENCE_SPEC if both are null
internal val DEFAULT_OCI_REFERENCE_SPEC = OciImageReferenceSpec(null, null)

internal class OciLayer( // TODO internal?
    val descriptor: OciMetadata.Layer.Descriptor,
    val file: File,
)

internal class OciVariant(
    val metadata: OciMetadata,
    val layers: List<OciLayer>,
)

internal class OciImage(
    val manifest: OciDataDescriptor,
    val config: OciDataDescriptor,
    val variants: List<OciVariant>,
)

internal class OciMultiArchImage(
    val index: OciData,
    val platformToImage: Map<Platform, OciImage>,
)

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imageInputs = project.objects.setProperty<OciImageInput>()

    init {
        @Suppress("LeakingThis")
        dependsOn(imageInputs)
    }

    fun from(dependencies: ResolvableOciImageDependencies) = imageInputs.addAll(dependencies.asInput())

    @TaskAction
    protected fun run() {
        val imageInputs: Set<OciImageInput> = imageInputs.get()
        // digestToLayerFile map is linked because it will be iterated
        val digestToLayerFile = LinkedHashMap<OciDigest, File>()
        val duplicateLayerFiles = HashSet<File>()
        val variantInputToVariant = HashMap<OciVariantInput, OciVariant>()
        val images = ArrayList<OciImage>()
        // referenceToPlatformToImage map is linked because it will be iterated
        // platformToImage map is linked to preserve the platform order
        val referenceToPlatformToImage = LinkedHashMap<OciImageReference, LinkedHashMap<Platform, OciImage>>()
        for (imageInput in imageInputs) {
            val variants = imageInput.variants.map { variantInput ->
                variantInputToVariant.getOrPut(variantInput) {
                    val metadata = variantInput.metadataFile.readText().decodeAsJsonToOciMetadata()
                    val layerFiles = variantInput.layerFiles
                    val layers = ArrayList<OciLayer>(layerFiles.size) // TODO fun associateLayerMetadataAndFiles
                    var layerFileIndex = 0
                    for (layer in metadata.layers) {
                        val layerDescriptor = layer.descriptor ?: continue
                        if (layerFileIndex >= layerFiles.size) {
                            throw IllegalStateException("count of layer descriptors (${layerFileIndex + 1}+) and layer files (${layerFiles.size}) do not match")
                        }
                        val layerFile = layerFiles[layerFileIndex++]
                        layers += OciLayer(layerDescriptor, layerFile)
                    }
                    if (layerFileIndex < layerFiles.size) {
                        throw IllegalStateException("count of layer descriptors ($layerFileIndex) and layer files (${layerFiles.size}) do not match")
                    }
                    for (layer in layers) {
                        val prevLayerFile = digestToLayerFile.putIfAbsent(layer.descriptor.digest, layer.file)
                        if ((prevLayerFile != null) && (prevLayerFile != layer.file) && duplicateLayerFiles.add(layer.file)) {
                            checkDuplicateLayer(layer.descriptor, prevLayerFile, layer.file)
                        }
                    }
                    OciVariant(metadata, layers)
                }
            }
            val config = createConfig(imageInput.platform, variants)
            val manifest = createManifest(config, variants)
            val image = OciImage(manifest, config, variants)
            images += image

            val defaultImageReference = variants.last().metadata.imageReference
            // imageReferences set is linked because it will be iterated
            val imageReferences = imageInput.referenceSpecs.mapTo(LinkedHashSet()) {
                OciImageReference(it.name ?: defaultImageReference.name, it.tag ?: defaultImageReference.tag)
            }.ifEmpty { setOf(defaultImageReference) }
            val platform = imageInput.platform
            for (imageReference in imageReferences) {
                // platformToImage map is linked to preserve the platform order
                val platformToImage = referenceToPlatformToImage.getOrPut(imageReference) { LinkedHashMap() }
                val prevImage = platformToImage.putIfAbsent(platform, image)
                if (prevImage != null) {
                    throw IllegalStateException("only one image with platform $platform can be referenced by the same image reference '$imageReference'")
                }
            }
        }
        // multiArchImageAndReferencesPairMap is linked because it will be iterated
        val multiArchImageAndReferencesPairMap =
            LinkedHashMap<Map<Platform, OciImage>, Pair<OciMultiArchImage, ArrayList<OciImageReference>>>() // TODO reference non multi arch images?
        for ((reference, platformToImage) in referenceToPlatformToImage) {
            var multiArchImageAndReferencesPair = multiArchImageAndReferencesPairMap[platformToImage]
            if (multiArchImageAndReferencesPair == null) {
                val index = createIndex(platformToImage)
                multiArchImageAndReferencesPair = Pair(OciMultiArchImage(index, platformToImage), ArrayList()) // TODO ArrayList
                multiArchImageAndReferencesPairMap[platformToImage] = multiArchImageAndReferencesPair
            }
            multiArchImageAndReferencesPair.second += reference
        }
        run(digestToLayerFile, images, multiArchImageAndReferencesPairMap.values.toList())
    }

    internal abstract fun run( // TODO internal? protected?
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiArchImageAndReferencesPairs: List<Pair<OciMultiArchImage, List<OciImageReference>>>,
    )

    private fun checkDuplicateLayer(layerDescriptor: OciMetadata.Layer.Descriptor, file1: File, file2: File) {
        if (!FileUtils.contentEquals(file1, file2)) {
            throw IllegalStateException("hash collision for digest ${layerDescriptor.digest}: expected file contents of $file1 and $file2 to be the same")
        }
        if (layerDescriptor.diffId !in EMPTY_LAYER_DIFF_IDS) {
            logger.warn("the same layer (${layerDescriptor.digest}) should not be provided by multiple artifacts ($file1, $file2)")
        }
    }
}

// empty tar = 1024 bytes zeros
private val EMPTY_LAYER_DIFF_IDS = setOf(
    "sha256:5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef".toOciDigest(),
    "sha512:8efb4f73c5655351c444eb109230c556d39e2c7624e9c11abc9e3fb4b9b9254218cc5085b454a9698d085cfa92198491f07a723be4574adc70617b73eb0b6461".toOciDigest(),
)
