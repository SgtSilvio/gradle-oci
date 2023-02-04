package io.github.sgtsilvio.gradle.oci.old

import io.github.sgtsilvio.gradle.oci.internal.calculateSha256Digest
import io.github.sgtsilvio.gradle.oci.internal.json.*
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
        val jsonBytes = jsonObject {
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            addString("architecture", platform.architecture.get())
            addStringIfNotNull("author", author.orNull)
            addObject("config") {
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                addArrayIfNotEmpty("Cmd", arguments.orNull)
                addArrayIfNotEmpty("Entrypoint", entryPoint.orNull)
                addArrayIfNotEmpty("Env", environment.orNull?.map { "${it.key}=${it.value}" })
                addObjectIfNotEmpty("ExposedPorts", ports.orNull)
                addObjectIfNotEmpty("Labels", annotations.orNull)
                addStringIfNotNull("StopSignal", stopSignal.orNull)
                addStringIfNotNull("User", user.orNull)
                addObjectIfNotEmpty("Volumes", volumes.orNull)
                addStringIfNotNull("WorkingDir", workingDirectory.orNull)
            }
            addStringIfNotNull("created", creationTime.orNull?.toString())
            addArray("history", layers) { layer ->
                addObject {
                    // sorted for canonical json: author, comment, created, created_by, empty_layer
                    addStringIfNotNull("author", layer.author.orNull)
                    addStringIfNotNull("comment", layer.comment.orNull)
                    addStringIfNotNull("created", layer.creationTime.orNull?.toString())
                    addStringIfNotNull("created_by", layer.createdBy.orNull)
                    if (!layer.diffId.isPresent) {
                        addBoolean("empty_layer", true)
                    }
                }
            }
            addString("os", platform.os.get())
            addArrayIfNotEmpty("os.features", platform.osFeatures.orNull)
            addStringIfNotNull("os.version", platform.osVersion.orNull)
            addObject("rootfs") {
                // sorted for canonical json: diff_ids, type
                addArray("diff_ids", layers) { layer ->
                    if (layer.diffId.isPresent) {
                        addString(layer.diffId.get())
                    }
                }
                addString("type", "layers")
            }
            addStringIfNotNull("variant", platform.variant.orNull)
        }.toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}