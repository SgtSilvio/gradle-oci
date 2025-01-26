package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.internal.copyspec.DEFAULT_MODIFICATION_TIME
import io.github.sgtsilvio.gradle.oci.internal.gradle.redirectOutput
import io.github.sgtsilvio.gradle.oci.internal.string.LineOutputStream
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatformArgument
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.file.attribute.FileTime
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class DockerLayerTask @Inject constructor(private val execOperations: ExecOperations) : OciLayerTask() {

    @get:Input
    val from = project.objects.property<String>() // TODO replace from and platform with ImageInput

    @get:Input
    val platform = project.objects.property<Platform>()

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

    override fun run(tarOutputStream: TarArchiveOutputStream) {
        val imageReference = UUID.randomUUID()
        val platformArgument = platform.get().toPlatformArgument()
        execOperations.exec {
            commandLine("docker", "build", "-", "--platform", platformArgument, "-t", imageReference, "--no-cache")
            standardInput = ByteArrayInputStream(assembleDockerfile().toByteArray())
            errorOutput = createCombinedErrorAndInfoOutputStream(logger)
        }
        val temporaryDirectory = temporaryDir
        val savedImageTarFile = temporaryDirectory.resolve("image.tar")
        execOperations.exec {
            commandLine("docker", "save", imageReference, "-o", savedImageTarFile)
            errorOutput = createCombinedErrorAndInfoOutputStream(logger)
        }
        execOperations.exec {
            commandLine("docker", "rmi", imageReference)
            redirectOutput(logger)
        }
        val manifest = TarArchiveInputStream(FileInputStream(savedImageTarFile)).use { savedImageTarInputStream ->
            if (!savedImageTarInputStream.findEntry("manifest.json")) {
                throw IllegalStateException("manifest.json not found in docker image export")
            }
            savedImageTarInputStream.reader().readText()
        }
        val lastLayerPath =
            manifest.substringAfter("\"Layers\":[").substringBefore("]").split(",").last().removeSurrounding("\"")
        TarArchiveInputStream(FileInputStream(savedImageTarFile)).use { savedImageTarInputStream ->
            if (!savedImageTarInputStream.findEntry(lastLayerPath)) {
                throw IllegalStateException("$lastLayerPath not found in docker image export")
            }
            TarArchiveInputStream(savedImageTarInputStream).use { layerTarInputStream ->
                while (layerTarInputStream.nextEntry != null) {
                    val tarEntry = layerTarInputStream.currentEntry
                    tarEntry.lastModifiedTime = FileTime.from(DEFAULT_MODIFICATION_TIME)
                    tarOutputStream.putArchiveEntry(tarEntry)
                    layerTarInputStream.copyTo(tarOutputStream)
                    tarOutputStream.closeArchiveEntry()
                }
            }
        }
        temporaryDirectory.deleteRecursively()
    }

    private fun assembleDockerfile() = buildString {
        val from = from.get()
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
            appendLine("ENV ").append(environment.map { "${it.key}=\"${it.value}\"" }.joinToString(" "))
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

    private fun TarArchiveInputStream.findEntry(path: String): Boolean {
        while (nextEntry != null) {
            if (currentEntry.name == path) {
                return true
            }
        }
        return false
    }
}
