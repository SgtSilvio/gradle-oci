package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.ResolvedOciComponent
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

/**
 * @author Silvio Giebl
 */
abstract class DistributionRegistryDataTask : OciImagesInputTask() {

    @get:OutputDirectory
    val registryDataDirectory: DirectoryProperty = project.objects.directoryProperty()

    override fun run(
        resolvedComponentToImageReferences: Map<ResolvedOciComponent, Set<OciImageReference>>,
        digestToLayer: Map<OciDigest, File>,
    ) {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()
        val blobsDirectory = registryDataDirectory.resolve("blobs").createDirectory()
        val repositoriesDirectory = registryDataDirectory.resolve("repositories").createDirectory()

        for ((digest, layer) in digestToLayer) {
            blobsDirectory.resolveDigestDataFile(digest).createLinkPointingTo(layer.toPath())
        }
        for ((resolvedComponent, imageReferences) in resolvedComponentToImageReferences) {
            writeImage(resolvedComponent, imageReferences, blobsDirectory, repositoriesDirectory)
        }
    }

    private fun writeImage(
        resolvedComponent: ResolvedOciComponent,
        imageReferences: Set<OciImageReference>,
        blobsDirectory: Path,
        repositoriesDirectory: Path,
    ) {
        val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
        val blobDigests = mutableSetOf<OciDigest>()
        for (platform in resolvedComponent.platforms) {
            val bundlesForPlatform = resolvedComponent.collectBundlesForPlatform(platform).map { it.bundle }
            for (bundle in bundlesForPlatform) {
                for (layer in bundle.layers) {
                    layer.descriptor?.let {
                        blobDigests += it.digest
                    }
                }
            }
            val config = createConfig(platform, bundlesForPlatform)
            blobsDirectory.writeDigestData(config)
            blobDigests += config.digest
            val manifest = createManifest(config, bundlesForPlatform)
            blobsDirectory.writeDigestData(manifest)
            manifests += Pair(platform, manifest)
        }
        val index = createIndex(manifests, resolvedComponent.component)
        blobsDirectory.writeDigestData(index)
        val indexDigest = index.digest

        for (imageReference in imageReferences) {
            val repositoryDirectory = repositoriesDirectory.resolve(imageReference.name).createDirectories()
            val layersDirectory = repositoryDirectory.resolve("_layers").createDirectories()
            for (blobDigest in blobDigests) {
                layersDirectory.writeDigestLink(blobDigest)
            }
            val manifestsDirectory = repositoryDirectory.resolve("_manifests").createDirectories()
            val manifestRevisionsDirectory = manifestsDirectory.resolve("revisions").createDirectories()
            for ((_, manifestDescriptor) in manifests) {
                manifestRevisionsDirectory.writeDigestLink(manifestDescriptor.digest)
            }
            manifestRevisionsDirectory.writeDigestLink(indexDigest)
            val tagDirectory = manifestsDirectory.resolve("tags").resolve(imageReference.tag).createDirectories()
            tagDirectory.writeTagLink(indexDigest)
            tagDirectory.resolve("index").createDirectories().writeDigestLink(indexDigest)
        }
    }

    private fun Path.resolveDigestDataFile(digest: OciDigest): Path {
        val encodedHash = digest.encodedHash
        return resolve(digest.algorithm.ociPrefix).resolve(encodedHash.substring(0, 2))
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
        resolve(digest.algorithm.ociPrefix).resolve(digest.encodedHash)
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
