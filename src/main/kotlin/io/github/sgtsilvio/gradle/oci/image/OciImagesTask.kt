package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import java.io.File

/**
 * @author Silvio Giebl
 */
abstract class OciImagesTask : DefaultTask() {

    @get:Nested
    val images = project.objects.setProperty<ImageInput>()

    data class ImageInput(
        @get:Input val platform: Platform,
        @get:Nested val variants: List<VariantInput>,
        @get:Input val referenceSpecs: Set<OciImageReferenceSpec>,
    ) {
        init {
            require(variants.isNotEmpty()) { "variants must not be empty" }
        }
    }

    data class VariantInput(
        @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val metadataFile: File,
        @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val layerFiles: List<File>,
    )

    @get:Internal
    val platformSelector = project.objects.property<PlatformSelector>()

    init {
        @Suppress("LeakingThis")
        dependsOn(images)
    }

    fun from(dependencies: OciImageDependencies) = images.addAll(dependencies.resolve(platformSelector))

    fun from(dependenciesListProvider: Provider<List<OciImageDependencies>>) {
        val objectFactory = project.objects
        images.addAll(dependenciesListProvider.flatMap { dependenciesList ->
            val listProperty = objectFactory.listProperty<ImageInput>()
            for (dependencies in dependenciesList) {
                listProperty.addAll(dependencies.resolve(platformSelector))
            }
            listProperty
        })
    }

    @Option(
        option = "platform",
        description = "Selects the platform specified in the format: <os>,<arch>[,<variant>[,<osVersion>[,<osFeature>(,<osFeature>)*]]]. Option can be specified multiple times. If not specified, all supported platforms are selected.",
    )
    protected fun selectPlatforms(platforms: List<String>) =
        platformSelector.set(platforms.map { PlatformSelector(it.toPlatform()) }.reduce(PlatformSelector::and))

    @TaskAction
    protected fun run() {
        val imageInputs: Set<ImageInput> = images.get()
        val imageAndReferencesPairs = createImageAndReferencesPairs(imageInputs)
        val multiPlatformImageAndReferencesPairs = createMultiPlatformImageAndReferencesPairs(imageAndReferencesPairs)
        val images = imageAndReferencesPairs.map { it.first }
        val digestToLayerFile = collectDigestToLayerFile(images)
        run(digestToLayerFile, images, multiPlatformImageAndReferencesPairs)
    }

    protected abstract fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    )

    private fun createImageAndReferencesPairs(
        imageInputs: Iterable<ImageInput>,
    ): List<Pair<OciImage, Set<OciImageReference>>> {
        val variantInputToVariant = HashMap<VariantInput, OciVariant>()
        return imageInputs.map { imageInput ->
            val variants = imageInput.variants.map { variantInput ->
                variantInputToVariant.getOrPut(variantInput) { variantInput.toVariant() }
            }
            val platform = imageInput.platform
            val config = createConfig(platform, variants)
            val manifest = createManifest(config, variants)
            val image = OciImage(manifest, config, platform, variants)

            val defaultImageReference = variants.last().metadata.imageReference
            // imageReferences set is linked because it will be iterated
            val imageReferences = imageInput.referenceSpecs.mapTo(LinkedHashSet()) {
                it.materialize(defaultImageReference)
            }.ifEmpty { setOf(defaultImageReference) }
            Pair(image, imageReferences)
        }
    }

    private fun VariantInput.toVariant(): OciVariant {
        val metadata = metadataFile.readText().decodeAsJsonToOciMetadata()
        val layerFiles = layerFiles
        val layers = ArrayList<OciLayer>(layerFiles.size)
        var layerFilesIndex = 0
        for (layer in metadata.layers) {
            val layerDescriptor = layer.descriptor ?: continue
            if (layerFilesIndex >= layerFiles.size) {
                throw IllegalStateException("count of layer descriptors (${layerFilesIndex + 1}+) and layer files (${layerFiles.size}) do not match")
            }
            val layerFile = layerFiles[layerFilesIndex++]
            layers += OciLayer(layerDescriptor, layerFile)
        }
        if (layerFilesIndex < layerFiles.size) {
            throw IllegalStateException("count of layer descriptors ($layerFilesIndex) and layer files (${layerFiles.size}) do not match")
        }
        return OciVariant(metadata, layers)
    }

    private fun createMultiPlatformImageAndReferencesPairs(
        imageAndReferencesPairs: Iterable<Pair<OciImage, Set<OciImageReference>>>,
    ): List<Pair<OciMultiPlatformImage, List<OciImageReference>>> {
        // referenceToPlatformToImage map is linked because it will be iterated
        val referenceToPlatformToImage = LinkedHashMap<OciImageReference, LinkedHashMap<Platform, OciImage>>()
        for ((image, imageReferences) in imageAndReferencesPairs) {
            for (imageReference in imageReferences) {
                // platformToImage map is linked to preserve the platform order
                val platformToImage = referenceToPlatformToImage.getOrPut(imageReference) { LinkedHashMap() }
                val prevImage = platformToImage.putIfAbsent(image.platform, image)
                if (prevImage != null) {
                    throw IllegalStateException("only one image with platform ${image.platform} can be referenced by the same image reference '$imageReference'")
                }
            }
        }
        // multiPlatformImageAndReferencesPairMap is linked because it will be iterated
        val multiPlatformImageAndReferencesPairMap =
            LinkedHashMap<Map<Platform, OciImage>, Pair<OciMultiPlatformImage, ArrayList<OciImageReference>>>()
        for ((reference, platformToImage) in referenceToPlatformToImage) {
            var multiPlatformImageAndReferencesPair = multiPlatformImageAndReferencesPairMap[platformToImage]
            if (multiPlatformImageAndReferencesPair == null) {
                val index = createIndex(platformToImage.values)
                multiPlatformImageAndReferencesPair = Pair(OciMultiPlatformImage(index, platformToImage), ArrayList())
                multiPlatformImageAndReferencesPairMap[platformToImage] = multiPlatformImageAndReferencesPair
            }
            multiPlatformImageAndReferencesPair.second += reference
        }
        return multiPlatformImageAndReferencesPairMap.values.toList()
    }

    private fun collectDigestToLayerFile(images: List<OciImage>): Map<OciDigest, File> {
        // digestToLayerFile map is linked because it will be iterated
        val digestToLayerFile = LinkedHashMap<OciDigest, File>()
        val duplicateLayerFiles = HashSet<File>()
        for (image in images) {
            for (variant in image.variants) {
                for (layer in variant.layers) {
                    val prevLayerFile = digestToLayerFile.putIfAbsent(layer.descriptor.digest, layer.file)
                    if ((prevLayerFile != null) && (prevLayerFile != layer.file) && duplicateLayerFiles.add(layer.file)) {
                        checkDuplicateLayer(layer.descriptor, prevLayerFile, layer.file)
                    }
                }
            }
        }
        return digestToLayerFile
    }

    private fun checkDuplicateLayer(layerDescriptor: OciLayerDescriptor, file1: File, file2: File) {
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

interface OciImageTask {

    @get:Internal
    @get:Option(
        option = "name",
        description = "Names the image. If not specified, the imageName defined in the image definition is used.",
    )
    val imageName: Property<String>

    @get:Internal
    @get:Option(
        option = "tag",
        description = "Tags the image. Option can be specified multiple times. The value '.' translates to the imageTag defined in the image definition. If not specified, the imageTag defined in the image definition is used.",
    )
    val imageTags: SetProperty<String>
}
