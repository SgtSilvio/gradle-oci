package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.ensureEmptyDirectory
import io.github.sgtsilvio.gradle.oci.internal.json.addArray
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonArray
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.internal.string.concatKebabCase
import io.github.sgtsilvio.gradle.oci.metadata.*
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*

/**
 * @author Silvio Giebl
 */
abstract class OciImagesLayoutTask : OciImagesTask() {

    @get:Input
    @get:Option(
        option = "docker-load-compatible",
        description = "Creates a manifest.json file for backwards compatibility with the docker save/load format",
    )
    val dockerLoadCompatible = project.objects.property<Boolean>().convention(true)

    @get:Internal
    val destinationDirectory = project.objects.directoryProperty()

    @get:Internal
    val classifier = project.objects.property<String>()

    @get:OutputDirectory
    val outputDirectory = project.objects.directoryProperty().convention(destinationDirectory.dir(classifier))

    @Option(option = "tar", description = "Creates a tar of this OCI image layout.")
    protected fun createTar(isCreateTar: Boolean) {
        if (isCreateTar) {
            finalizedBy(project.tasks.named("${name}Tar"))
        }
    }

    final override fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    ) {
        val outputDirectory = outputDirectory.get().asFile.toPath()
        outputDirectory.ensureEmptyDirectory()
        outputDirectory.resolve("oci-layout").writeText("""{"imageLayoutVersion":"1.0.0"}""")
        outputDirectory.resolve("index.json")
            .writeBytes(createIndexJson(multiPlatformImageAndReferencesPairs).toByteArray())
        if (dockerLoadCompatible.get()) {
            outputDirectory.resolve("manifest.json")
                .writeBytes(createManifestJson(multiPlatformImageAndReferencesPairs).toByteArray())
        }
        val blobsDirectory = outputDirectory.resolve("blobs").createDirectory()
        for (image in images) {
            blobsDirectory.writeBlob(image.config.data)
            blobsDirectory.writeBlob(image.manifest.data)
        }
        for ((multiPlatformImage, _) in multiPlatformImageAndReferencesPairs) {
            blobsDirectory.writeBlob(multiPlatformImage.index)
        }
        for ((digest, layerFile) in digestToLayerFile) {
            blobsDirectory.resolveBlobFile(digest).createLinkPointingTo(layerFile.toPath())
        }
    }

    private fun createIndexJson(
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    ) = jsonObject {
        // sorted for canonical json: manifests, mediaType, schemaVersion
        addArray("manifests") {
            for ((multiPlatformImage, imageReferences) in multiPlatformImageAndReferencesPairs) {
                for (imageReference in imageReferences) {
                    val annotations = sortedMapOf(
                        "org.opencontainers.image.ref.name" to imageReference.toString(),
                        "io.containerd.image.name" to "docker.io/$imageReference",
                    )
                    val descriptor = OciDataDescriptor(multiPlatformImage.index, annotations)
                    addObject { encodeOciDescriptor(descriptor) }
                }
            }
        }
        addString("mediaType", INDEX_MEDIA_TYPE)
        addNumber("schemaVersion", 2)
    }

    private fun createManifestJson(
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    ) = jsonArray {
        for ((multiPlatformImage, imageReferences) in multiPlatformImageAndReferencesPairs) {
            val addArchitectureToTag = multiPlatformImage.platformToImage.size > 1
            val addOsToTag = multiPlatformImage.platformToImage.keys.mapTo(HashSet()) { it.os }.size > 1
            for ((platform, image) in multiPlatformImage.platformToImage) {
                addObject {
                    addString("Config", relativeBlobFilePath(image.config.digest))
                    addArray("Layers") {
                        for (variant in image.variants) {
                            for (layer in variant.layers) {
                                addString(relativeBlobFilePath(layer.descriptor.digest))
                            }
                        }
                    }
                    var tagSuffix = if (addOsToTag) platform.os else ""
                    if (addArchitectureToTag) {
                        tagSuffix = tagSuffix.concatKebabCase(platform.architecture).concatKebabCase(platform.variant)
                    }
                    addArray("RepoTags", imageReferences.map { it.toString().concatKebabCase(tagSuffix) })
                }
            }
        }
    }

    private fun relativeBlobFilePath(digest: OciDigest) = "blobs/${digest.algorithm.id}/${digest.encodedHash}"

    private fun Path.resolveBlobFile(digest: OciDigest) =
        resolve(digest.algorithm.id).createDirectories().resolve(digest.encodedHash)

    private fun Path.writeBlob(data: OciData) = resolveBlobFile(data.digest).writeBytes(data.bytes)
}

abstract class OciImageLayoutTask : OciImagesLayoutTask(), OciImageTask
