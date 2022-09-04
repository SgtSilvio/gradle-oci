package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import java.time.Instant

/**
 * @author Silvio Giebl
 */
abstract class OciConfigTask : DefaultTask() {

    @get:Input
    @get:Optional
    val creationTime = project.objects.property<Instant>()

    @get:Input
    @get:Optional
    val author = project.objects.property<String>()

    @get:Nested
    val platform = project.objects.newInstance<OciPlatform>()

    @get:Input
    @get:Optional
    val user = project.objects.property<String>()

    @get:Input
    @get:Optional
    val ports = project.objects.setProperty<String>()

    @get:Input
    @get:Optional
    val environment = project.objects.mapProperty<String, String>()

    @get:Input
    @get:Optional
    val entryPoint = project.objects.listProperty<String>()

    @get:Input
    @get:Optional
    val arguments = project.objects.listProperty<String>()

    @get:Input
    @get:Optional
    val volumes = project.objects.setProperty<String>()

    @get:Input
    @get:Optional
    val workingDirectory = project.objects.property<String>()

    @get:Input
    @get:Optional
    val labels = project.objects.mapProperty<String, String>()

    @get:Input
    @get:Optional
    val stopSignal = project.objects.property<String>()

    @get:Input
    val layerDiffIds = project.objects.listProperty<String>()

    @get:Nested
    @get:Optional
    val history = project.objects.listProperty<HistoryEntry>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("config.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("config.digest"))

    interface HistoryEntry {
        @get:Input
        @get:Optional
        val creationTime: Property<Instant>

        @get:Input
        @get:Optional
        val author: Property<String>

        @get:Input
        @get:Optional
        val createdBy: Property<String>

        @get:Input
        @get:Optional
        val comment: Property<String>

        @get:Input
        @get:Optional
        val emptyLayer: Property<Boolean>
    }

    @TaskAction
    protected fun run() {
        val jsonStringBuilder = jsonStringBuilder()
        jsonStringBuilder.addObject { rootObject ->
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            rootObject.addKey("architecture").addValue(platform.architecture.get())
            rootObject.addOptionalKeyAndValue("author", author.orNull)
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                configObject.addOptionalKeyAndArray("Cmd", arguments.orNull)
                configObject.addOptionalKeyAndArray("Entrypoint", entryPoint.orNull)
                configObject.addOptionalKeyAndArray("Env", environment.orNull?.map { "${it.key}=${it.value}" })
                configObject.addOptionalKeyAndObject("ExposedPorts", ports.orNull)
                configObject.addOptionalKeyAndObject("Labels", labels.orNull)
                configObject.addOptionalKeyAndValue("StopSignal", stopSignal.orNull)
                configObject.addOptionalKeyAndValue("User", user.orNull)
                configObject.addOptionalKeyAndObject("Volumes", volumes.orNull)
                configObject.addOptionalKeyAndValue("WorkingDir", workingDirectory.orNull)
            }
            rootObject.addOptionalKeyAndValue("created", creationTime.orNull?.toString())
            history.orNull?.takeIf { it.isNotEmpty() }?.let { history ->
                rootObject.addKey("history").addArray { historyArray ->
                    history.forEach { historyEntry ->
                        historyArray.addObject { historyEntryObject ->
                            // sorted for canonical json: author, comment, created, created_by, empty_layer
                            historyEntryObject.addOptionalKeyAndValue("author", historyEntry.author.orNull)
                            historyEntryObject.addOptionalKeyAndValue("comment", historyEntry.comment.orNull)
                            historyEntryObject.addOptionalKeyAndValue(
                                "created", historyEntry.creationTime.orNull?.toString()
                            )
                            historyEntryObject.addOptionalKeyAndValue("created_by", historyEntry.createdBy.orNull)
                            if (historyEntry.emptyLayer.getOrElse(false)) {
                                historyEntryObject.addKey("empty_layer").addValue(true)
                            }
                        }
                    }
                }
            }
            rootObject.addKey("os").addValue(platform.os.get())
            rootObject.addOptionalKeyAndArray("os.features", platform.osFeatures.orNull)
            rootObject.addOptionalKeyAndValue("os.version", platform.osVersion.orNull)
            rootObject.addKey("rootfs").addObject { rootfsObject ->
                // sorted for canonical json: diff_ids, type
                rootfsObject.addKey("diff_ids").addArray(layerDiffIds.get())
                rootfsObject.addKey("type").addValue("layers")
            }
            rootObject.addOptionalKeyAndValue("variant", platform.variant.orNull)
        }
        val jsonBytes = jsonStringBuilder.toString().toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}