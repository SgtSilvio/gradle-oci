package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.image.*
import io.github.sgtsilvio.gradle.oci.image.OciImagesTask.VariantInput
import io.github.sgtsilvio.gradle.oci.internal.copyspec.DEFAULT_MODIFICATION_TIME
import io.github.sgtsilvio.gradle.oci.internal.findExecutablePath
import io.github.sgtsilvio.gradle.oci.internal.string.LineOutputStream
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import io.github.sgtsilvio.gradle.oci.platform.toPlatformArgument
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.file.attribute.FileTime
import java.util.*
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/**
 * @author Silvio Giebl
 */
abstract class DockerLayerTask @Inject constructor(private val execOperations: ExecOperations) : OciLayerTask() {

    @get:Nested
    val parentVariants = project.objects.listProperty<VariantInput>()

    @get:Input
    val platform = project.objects.property<Platform>()

//    interface ImageInput {
//
//        @get:Input
//        val platform: Property<Platform>
//
//        @get:Nested
//        val variants: ListProperty<VariantInput>
//    }

    @get:Input
    val command = project.objects.property<String>()

    @get:Input
    @get:Optional
    val shell = project.objects.property<String>()

    @get:Input
    @get:Optional
    val user = project.objects.property<String>()

    @get:Input
    @get:Optional
    val workingDirectory = project.objects.property<String>()

    @get:Input
    val environment = project.objects.mapProperty<String, String>()

//    fun from(dependency: ModuleDependency) {
//        val ociExtension = project.extensions.getByType(OciExtension::class)
//        ociExtension.imageDependencies.maybeCreate()
//    }

    fun from(imageDependencies: OciImageDependencies) {
        val platformSelector = platform.map { PlatformSelector(it) }
        val imageInputs = imageDependencies.resolve(platformSelector)
        val variantInputs = imageInputs.map { imageInputs -> imageInputs.flatMap { it.variants } }
        parentVariants.addAll(variantInputs)
    }

    override fun run(tarOutputStream: TarArchiveOutputStream) {
        val dockerExecutablePath = findExecutablePath("docker")
        val imageName = UUID.randomUUID().toString()
        val inputImageTag = "input"
        val outputImageTag = "output"
        val platform = platform.get()
        val temporaryDirectory = temporaryDir
        val registryDataDirectory = temporaryDirectory.toPath().resolve("registry")
        val parentImage = createImage(platform, parentVariants.get())
        createRegistryDataDirectory(
            collectDigestToLayerFile(listOf(parentImage), logger),
            listOf(parentImage),
            listOf(Pair(parentImage.toMultiPlatformImage(), listOf(OciImageReference(imageName, inputImageTag)))),
            registryDataDirectory,
        )
        useRegistry(registryDataDirectory) { registryPort ->
            val repository = "${getDockerHost()}:$registryPort/$imageName"
            execOperations.exec {
                executable = dockerExecutablePath
                args = listOf(
                    "build",
                    "--platform",
                    platform.toPlatformArgument(),
                    "-o",
                    "type=registry,store=false,name=$repository:$outputImageTag",
                    "--no-cache",
                    "-",
                )
                standardInput = ByteArrayInputStream(assembleDockerfile("$repository:$inputImageTag").toByteArray())
                errorOutput = createCombinedErrorAndInfoOutputStream(logger)
            }
        }
        val indexOrManifestDigest =
            registryDataDirectory.resolve("repositories/$imageName/_manifests/tags/$outputImageTag/current/link")
                .readText()
                .toOciDigest()
        val blobsDirectory = registryDataDirectory.resolve("blobs")
        val indexOrManifest = JSONObject(blobsDirectory.resolveDigestDataFile(indexOrManifestDigest).readText())
        val manifest = when (val mediaType = indexOrManifest.getString("mediaType")) {
            INDEX_MEDIA_TYPE, DOCKER_MANIFEST_LIST_MEDIA_TYPE -> {
                val manifestDescriptor = indexOrManifest.getJSONArray("manifests").first() as JSONObject // TODO first -> find mediaType and platform
                val manifestDigest = manifestDescriptor.getString("digest").toOciDigest()
                JSONObject(blobsDirectory.resolveDigestDataFile(manifestDigest).readText())
            }
            MANIFEST_MEDIA_TYPE, DOCKER_MANIFEST_MEDIA_TYPE -> indexOrManifest
            else -> throw IllegalStateException("unexpected index or manifest media type: $mediaType")
        }
        val layerDescriptor = manifest.getJSONArray("layers").last() as JSONObject
        val layerDigest = layerDescriptor.getString("digest").toOciDigest()
        val layerInputStream = blobsDirectory.resolveDigestDataFile(layerDigest).inputStream()
        val uncompressedLayerInputStream = when (val mediaType = layerDescriptor.getString("mediaType")) {
            UNCOMPRESSED_LAYER_MEDIA_TYPE -> layerInputStream
            GZIP_COMPRESSED_LAYER_MEDIA_TYPE, DOCKER_LAYER_MEDIA_TYPE -> GZIPInputStream(layerInputStream)
            else -> throw IllegalStateException("unexpected layer media type: $mediaType")
        }
        TarArchiveInputStream(uncompressedLayerInputStream).use { layerTarInputStream ->
            while (layerTarInputStream.nextEntry != null) {
                val tarEntry = layerTarInputStream.currentEntry
                tarEntry.lastModifiedTime = FileTime.from(DEFAULT_MODIFICATION_TIME)
                tarOutputStream.putArchiveEntry(tarEntry)
                layerTarInputStream.copyTo(tarOutputStream)
                tarOutputStream.closeArchiveEntry()
            }
        }
        temporaryDirectory.deleteRecursively()
    }

    private fun assembleDockerfile(from: String) = buildString {
        appendLine("FROM $from")
        val shell = shell.orNull
        if (shell != null) {
            appendLine("SHELL $shell")
        }
        val user = user.orNull
        if (user != null) {
            appendLine("USER $user")
        }
        val workingDirectory = workingDirectory.orNull
        if (workingDirectory != null) {
            appendLine("WORKDIR $workingDirectory")
        }
        val environment = environment.get()
        if (environment.isNotEmpty()) {
            append("ENV ")
            environment.entries.joinTo(this, " ") { "${it.key}=\"${it.value}\"" }
            appendLine()
        }
        appendLine("RUN echo \"Docker on MacOS creates a directory /root/.cache/rosetta in the first layer\"")
        val command = command.get()
        appendLine("RUN $command")
    }

    private fun createCombinedErrorAndInfoOutputStream(logger: Logger) = LineOutputStream { line ->
        if (line.startsWith("ERROR")) {
            logger.error(line)
        } else {
            logger.info(line)
        }
    }

    private fun createImage(platform: Platform, variantInputs: Iterable<VariantInput>): OciImage {
        val variants = variantInputs.map { variantInput -> variantInput.toVariant() } // TODO dedup variants
        return OciImage(platform, variants)
    }

    private fun OciImage.toMultiPlatformImage() = OciMultiPlatformImage(mapOf(platform to this))
}
