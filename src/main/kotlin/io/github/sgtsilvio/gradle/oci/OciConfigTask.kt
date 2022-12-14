package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import java.time.Instant
import javax.inject.Inject

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
    val stopSignal = project.objects.property<String>()

    @get:Nested
    val layers = mutableListOf<Layer>()

    @get:Input
    @get:Optional
    val annotations = project.objects.mapProperty<String, String>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("config.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("config.digest"))

    interface Layer {
        @get:Input
        @get:Optional
        val diffId: Property<String>

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
    }

    @get:Inject
    protected abstract val objectFactory: ObjectFactory

    fun platform(action: Action<in OciPlatform>) = action.execute(platform)

    fun addLayer(action: Action<in Layer>) {
        val layer = objectFactory.newInstance<Layer>()
        layers.add(layer)
        action.execute(layer)
    }

    @TaskAction
    protected fun run() {
        val jsonBytes = jsonObject { rootObject ->
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            rootObject.addKey("architecture").addString(platform.architecture.get())
            rootObject.addOptionalKeyAndString("author", author.orNull)
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                configObject.addOptionalKeyAndArray("Cmd", arguments.orNull)
                configObject.addOptionalKeyAndArray("Entrypoint", entryPoint.orNull)
                configObject.addOptionalKeyAndArray("Env", environment.orNull?.map { "${it.key}=${it.value}" })
                configObject.addOptionalKeyAndObject("ExposedPorts", ports.orNull)
                configObject.addOptionalKeyAndObject("Labels", annotations.orNull)
                configObject.addOptionalKeyAndString("StopSignal", stopSignal.orNull)
                configObject.addOptionalKeyAndString("User", user.orNull)
                configObject.addOptionalKeyAndObject("Volumes", volumes.orNull)
                configObject.addOptionalKeyAndString("WorkingDir", workingDirectory.orNull)
            }
            rootObject.addOptionalKeyAndString("created", creationTime.orNull?.toString())
            rootObject.addKey("history").addArray { historyArray ->
                for (layer in layers) {
                    historyArray.addObject { historyObject ->
                        // sorted for canonical json: author, comment, created, created_by, empty_layer
                        historyObject.addOptionalKeyAndString("author", layer.author.orNull)
                        historyObject.addOptionalKeyAndString("comment", layer.comment.orNull)
                        historyObject.addOptionalKeyAndString("created", layer.creationTime.orNull?.toString())
                        historyObject.addOptionalKeyAndString("created_by", layer.createdBy.orNull)
                        if (!layer.diffId.isPresent) {
                            historyObject.addKey("empty_layer").addBoolean(true)
                        }
                    }
                }
            }
            rootObject.addKey("os").addString(platform.os.get())
            rootObject.addOptionalKeyAndArray("os.features", platform.osFeatures.orNull)
            rootObject.addOptionalKeyAndString("os.version", platform.osVersion.orNull)
            rootObject.addKey("rootfs").addObject { rootfsObject ->
                // sorted for canonical json: diff_ids, type
                rootfsObject.addKey("diff_ids").addArray { diffIdsArray ->
                    for (layer in layers) {
                        if (layer.diffId.isPresent) {
                            diffIdsArray.addString(layer.diffId.get())
                        }
                    }
                }
                rootfsObject.addKey("type").addString("layers")
            }
            rootObject.addOptionalKeyAndString("variant", platform.variant.orNull)
        }.toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}