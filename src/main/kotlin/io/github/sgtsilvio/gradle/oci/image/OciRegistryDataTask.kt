package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.metadata.OciData
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
abstract class OciRegistryDataTask : OciImagesTask() {

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    final override fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    ) {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()
        val blobsDirectory = registryDataDirectory.resolve("blobs").createDirectory()
        val repositoriesDirectory = registryDataDirectory.resolve("repositories").createDirectory()
        for ((digest, layerFile) in digestToLayerFile) {
            blobsDirectory.resolveDigestDataFile(digest).createLinkPointingTo(layerFile.toPath())
        }
        for (image in images) {
            blobsDirectory.writeDigestData(image.config.data)
            blobsDirectory.writeDigestData(image.manifest.data)
        }
        for ((multiPlatformImage, imageReferences) in multiPlatformImageAndReferencesPairs) {
            blobsDirectory.writeDigestData(multiPlatformImage.index)
            for ((name, tags) in imageReferences.groupBy({ it.name }, { it.tag })) {
                val repositoryDirectory = repositoriesDirectory.resolve(name).createDirectories()
                val layersDirectory = repositoryDirectory.resolve("_layers").createDirectories()
                val manifestsDirectory = repositoryDirectory.resolve("_manifests").createDirectories()
                val manifestRevisionsDirectory = manifestsDirectory.resolve("revisions").createDirectories()
                val blobDigests = LinkedHashSet<OciDigest>()
                for (image in multiPlatformImage.platformToImage.values) {
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
                val indexDigest = multiPlatformImage.index.digest
                manifestRevisionsDirectory.writeDigestLink(indexDigest)
                for (tag in tags) {
                    val tagDirectory = manifestsDirectory.resolve("tags").resolve(tag).createDirectories()
                    tagDirectory.writeTagLink(indexDigest)
                    tagDirectory.resolve("index").createDirectories().writeDigestLink(indexDigest)
                }
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

    private fun Path.writeDigestData(data: OciData) {
        val digestDataFile = resolveDigestDataFile(data.digest)
        try {
            digestDataFile.writeBytes(data.bytes, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!data.bytes.contentEquals(digestDataFile.readBytes())) {
                throw IllegalStateException("hash collision for digest ${data.digest}: expected file content of $digestDataFile to be the same as ${data.bytes.contentToString()}")
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
