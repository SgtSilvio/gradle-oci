package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.metadata.OciDataDescriptor
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

/**
 * @author Silvio Giebl
 */
abstract class OciRegistryDataTask : OciImagesInputTask() {

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    override fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiArchImageAndReferencesPairs: List<Pair<OciMultiArchImage, List<OciImageReference>>>,
    ) {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()
        val blobsDirectory = registryDataDirectory.resolve("blobs").createDirectory()
        val repositoriesDirectory = registryDataDirectory.resolve("repositories").createDirectory()
        for ((digest, layerFile) in digestToLayerFile) {
            blobsDirectory.resolveDigestDataFile(digest).createLinkPointingTo(layerFile.toPath())
        }
        for (image in images) {
            blobsDirectory.writeDigestData(image.config)
            blobsDirectory.writeDigestData(image.manifest)
        }
        for ((multiArchImage, imageReferences) in multiArchImageAndReferencesPairs) {
            blobsDirectory.writeDigestData(multiArchImage.index)
            for (imageReference in imageReferences) { // TODO group by name
                val repositoryDirectory = repositoriesDirectory.resolve(imageReference.name).createDirectories()
                val layersDirectory = repositoryDirectory.resolve("_layers").createDirectories()
                val manifestsDirectory = repositoryDirectory.resolve("_manifests").createDirectories()
                val manifestRevisionsDirectory = manifestsDirectory.resolve("revisions").createDirectories()
                val blobDigests = LinkedHashSet<OciDigest>()
                for (image in multiArchImage.platformToImage.values) {
                    manifestRevisionsDirectory.writeDigestLink(image.manifest.digest)
                    blobDigests += image.config.digest
                    for (variant in image.variants) {
                        for (layer in variant.layers) {
                            blobDigests += layer.descriptor.digest
                        }
                    }
                }
                for (blobDigest in blobDigests) {
                    layersDirectory.writeDigestLink(blobDigest)
                }
                val indexDigest = multiArchImage.index.digest
                manifestRevisionsDirectory.writeDigestLink(indexDigest)
                val tagDirectory = manifestsDirectory.resolve("tags").resolve(imageReference.tag).createDirectories()
                tagDirectory.writeTagLink(indexDigest)
                tagDirectory.resolve("index").createDirectories().writeDigestLink(indexDigest)
            }
        }
    }

    private fun Path.resolveDigestDataFile(digest: OciDigest): Path {
        val encodedHash = digest.encodedHash
        return resolve(digest.algorithm.id).resolve(encodedHash.substring(0, 2))
            .resolve(encodedHash)
            .createDirectories()
            .resolve("data")
    }

    private fun Path.writeDigestData(dataDescriptor: OciDataDescriptor) {
        val digestDataFile = resolveDigestDataFile(dataDescriptor.digest)
        try {
            digestDataFile.writeBytes(dataDescriptor.data, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!dataDescriptor.data.contentEquals(digestDataFile.readBytes())) {
                throw IllegalStateException("hash collision for digest ${dataDescriptor.digest}: expected file content of $digestDataFile to be the same as ${dataDescriptor.data.contentToString()}")
            }
        }
    }

    private fun Path.writeDigestLink(digest: OciDigest) {
        resolve(digest.algorithm.id).resolve(digest.encodedHash)
            .createDirectories()
            .resolve("link")
            .writeBytes(digest.toString().toByteArray())
    }

    private fun Path.writeTagLink(digest: OciDigest) {
        val tagLinkFile: Path = resolve("current").createDirectories().resolve("link")
        val digestBytes = digest.toString().toByteArray()
        try {
            tagLinkFile.writeBytes(digestBytes, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!digestBytes.contentEquals(tagLinkFile.readBytes())) {
                throw IllegalStateException("tried to link the same image name/tag to different images")
            }
        }
    }
}

internal fun Path.ensureEmptyDirectory(): Path {
    if (!toFile().deleteRecursively()) {
        throw IOException("$this could not be deleted")
    }
    return createDirectories()
}
