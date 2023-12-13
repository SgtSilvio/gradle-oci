package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.ResolvedOciComponent
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
        val blobsDirectory: Path = registryDataDirectory.resolve("blobs")
        val repositoriesDirectory: Path = registryDataDirectory.resolve("repositories")

        for ((digest, layer) in digestToLayer) {
            Files.createLink(blobsDirectory.resolveDigestDataFile(digest), layer.toPath())
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
        val blobDigests = hashSetOf<OciDigest>()
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
            val repositoryDirectory: Path = Files.createDirectories(repositoriesDirectory.resolve(imageReference.name))
            val layersDirectory: Path = Files.createDirectories(repositoryDirectory.resolve("_layers"))
            for (blobDigest in blobDigests) {
                layersDirectory.writeDigestLink(blobDigest)
            }
            val manifestsDirectory: Path = Files.createDirectories(repositoryDirectory.resolve("_manifests"))
            val manifestRevisionsDirectory: Path = Files.createDirectories(manifestsDirectory.resolve("revisions"))
            for ((_, manifestDescriptor) in manifests) {
                manifestRevisionsDirectory.writeDigestLink(manifestDescriptor.digest)
            }
            manifestRevisionsDirectory.writeDigestLink(indexDigest)
            val tagDirectory: Path =
                Files.createDirectories(manifestsDirectory.resolve("tags").resolve(imageReference.tag))
            tagDirectory.writeTagLink(indexDigest)
            Files.createDirectories(tagDirectory.resolve("index")).writeDigestLink(indexDigest)
        }
    }

    private fun Path.resolveDigestDataFile(digest: OciDigest): Path {
        val encodedHash = digest.encodedHash
        return Files.createDirectories(
            resolve(digest.algorithm.ociPrefix).resolve(encodedHash.substring(0, 2)).resolve(encodedHash)
        ).resolve("data")
    }

    private fun Path.writeDigestData(dataDescriptor: OciDataDescriptor) {
        val digestDataFile = resolveDigestDataFile(dataDescriptor.digest)
        try {
            Files.write(digestDataFile, dataDescriptor.data, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!dataDescriptor.data.contentEquals(Files.readAllBytes(digestDataFile))) {
                throw IllegalStateException("hash collision for digest ${dataDescriptor.digest}: expected file content of $digestDataFile to be the same as ${dataDescriptor.data.contentToString()}")
            }
        }
    }

    private fun Path.writeDigestLink(digest: OciDigest) {
        Files.write(
            Files.createDirectories(resolve(digest.algorithm.ociPrefix).resolve(digest.encodedHash)).resolve("link"),
            digest.toString().toByteArray(),
        )
    }

    private fun Path.writeTagLink(digest: OciDigest) {
        val tagLinkFile: Path = Files.createDirectories(resolve("current")).resolve("link")
        val digestBytes = digest.toString().toByteArray()
        try {
            Files.write(tagLinkFile, digestBytes, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            if (!digestBytes.contentEquals(Files.readAllBytes(tagLinkFile))) {
                throw IllegalStateException("tried to link the same image name/tag to different images")
            }
        }
    }
}

private fun Path.ensureEmptyDirectory(): Path {
    if (!toFile().deleteRecursively()) {
        throw IOException("$this could not be deleted")
    }
    return Files.createDirectories(this)
}
