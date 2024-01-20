package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.ResolvedOciComponent
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
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
        resolvedComponentToImageReferences: Map<ResolvedOciComponent, Set<OciImageReference>>,
        digestToLayer: Map<OciDigest, File>,
    ) {
        val registryDataDirectory = registryDataDirectory.get().asFile.toPath().ensureEmptyDirectory()
        val blobsDirectory = registryDataDirectory.resolve("blobs").createDirectory()
        val repositoriesDirectory = registryDataDirectory.resolve("repositories").createDirectory()
        val layerDigests = mutableSetOf<OciDigest>()
        for ((resolvedComponent, imageReferences) in resolvedComponentToImageReferences) {
            writeImage(resolvedComponent, imageReferences, blobsDirectory, repositoriesDirectory, layerDigests)
        }
        for (digest in layerDigests) {
            blobsDirectory.resolveDigestFile(digest).createLinkPointingTo(digestToLayer[digest]!!.toPath())
        }
    }

    private fun writeImage(
        resolvedComponent: ResolvedOciComponent,
        imageReferences: Set<OciImageReference>,
        blobsDirectory: Path,
        repositoriesDirectory: Path,
        layerDigests: MutableSet<OciDigest>,
    ) {
        val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
        val blobDigests = mutableSetOf<OciDigest>()
        for (platform in resolvedComponent.platforms) {
            val bundlesForPlatform = resolvedComponent.collectBundlesForPlatform(platform).map { it.bundle }
            for (bundle in bundlesForPlatform) {
                for (layer in bundle.layers) {
                    layer.descriptor?.let { (_, digest) ->
                        layerDigests += digest
                        blobDigests += digest
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
            val repositoryBlobsDirectory = repositoryDirectory.resolve("blobs").createDirectories()
            for (blobDigest in blobDigests) {
                repositoryBlobsDirectory.writeDigestLink(blobDigest)
            }
            val manifestsDirectory = repositoryDirectory.resolve("manifests").createDirectories()
            for ((_, manifestDescriptor) in manifests) {
                manifestsDirectory.writeDigestLink(manifestDescriptor.digest)
            }
            manifestsDirectory.writeDigestLink(indexDigest)
            manifestsDirectory.resolve(imageReference.tag).writeTagLink(indexDigest)
        }
    }

    private fun Path.resolveDigestFile(digest: OciDigest): Path =
        resolve(digest.algorithm.ociPrefix).createDirectories().resolve(digest.encodedHash)

    private fun Path.writeDigestData(dataDescriptor: OciDataDescriptor) {
        val digestDataFile = resolveDigestFile(dataDescriptor.digest)
        try {
            digestDataFile.writeBytes(dataDescriptor.data, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!dataDescriptor.data.contentEquals(digestDataFile.readBytes())) {
                throw IllegalStateException("hash collision for digest ${dataDescriptor.digest}: expected file content of $digestDataFile to be the same as ${dataDescriptor.data.contentToString()}")
            }
        }
    }

    private fun Path.writeDigestLink(digest: OciDigest) {
        resolveDigestFile(digest).writeBytes(digest.toString().toByteArray())
    }

    private fun Path.writeTagLink(digest: OciDigest) {
        val digestBytes = digest.toString().toByteArray()
        try {
            writeBytes(digestBytes, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!digestBytes.contentEquals(readBytes())) {
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
