package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.internal.copyspec.DEFAULT_MODIFICATION_TIME
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.file.attribute.FileTime
import java.util.*

/**
 * @author Silvio Giebl
 */
abstract class DockerLayerTask(private val execOperations: ExecOperations) : OciLayerTask() {

    @get:Input
    val from = project.objects.property<String>() // TODO replace from and platform with ImageInput

    @get:Input
    val platform = project.objects.property<String>()

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

    override fun run(tos: TarArchiveOutputStream) {
        val imageReference = UUID.randomUUID()
        execOperations.exec {
            commandLine("docker", "build", "-", "--platform", platform.get(), "-t", imageReference)
            standardInput = ByteArrayInputStream(assembleDockerfile().toByteArray())
        }
        val tmpDir = temporaryDir
        val savedImageTarFile = tmpDir.resolve("image.tar")
        execOperations.exec {
            commandLine("docker", "save", imageReference, "-o", savedImageTarFile)
        }
        execOperations.exec {
            commandLine("docker", "rmi", imageReference)
        }
        val manifest = TarArchiveInputStream(FileInputStream(savedImageTarFile)).use { tis ->
            while (tis.nextEntry != null) {
                if (tis.currentEntry.name == "manifest.json") {
                    return@use tis.reader().readText()
                }
            }
            throw IllegalStateException("manifest.json not found")
        }
        val lastLayerPath =
            manifest.substringAfter("\"Layers\":[").substringBefore("]").split(",").last().removeSurrounding("\"")
        TarArchiveInputStream(FileInputStream(savedImageTarFile)).use { tis ->
            while (tis.nextEntry != null) {
                if (tis.currentEntry.name == lastLayerPath) {
                    TarArchiveInputStream(tis).use { tis2 ->
                        while (tis2.nextEntry != null) {
                            val entry2 = tis2.currentEntry
                            entry2.lastModifiedTime = FileTime.from(DEFAULT_MODIFICATION_TIME)
                            tos.putArchiveEntry(entry2)
                            tis2.copyTo(tos)
                            tos.closeArchiveEntry()
                        }
                    }
                    return@use
                }
            }
            throw IllegalStateException("$lastLayerPath not found")
        }
        tmpDir.deleteRecursively()
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
}
