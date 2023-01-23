package io.github.sgtsilvio.gradle.oci.old

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
        val jsonBytes = jsonObject {
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            addKey("architecture").addString(platform.architecture.get())
            addKeyAndStringIfNotNull("author", author.orNull)
            addKey("config").addObject {
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                addKeyAndArrayIfNotEmpty("Cmd", arguments.orNull)
                addKeyAndArrayIfNotEmpty("Entrypoint", entryPoint.orNull)
                addKeyAndArrayIfNotEmpty("Env", environment.orNull?.map { "${it.key}=${it.value}" })
                addKeyAndObjectIfNotEmpty("ExposedPorts", ports.orNull)
                addKeyAndObjectIfNotEmpty("Labels", annotations.orNull)
                addKeyAndStringIfNotNull("StopSignal", stopSignal.orNull)
                addKeyAndStringIfNotNull("User", user.orNull)
                addKeyAndObjectIfNotEmpty("Volumes", volumes.orNull)
                addKeyAndStringIfNotNull("WorkingDir", workingDirectory.orNull)
            }
            addKeyAndStringIfNotNull("created", creationTime.orNull?.toString())
            addKey("history").addArray {
                for (layer in layers) {
                    addObject {
                        // sorted for canonical json: author, comment, created, created_by, empty_layer
                        addKeyAndStringIfNotNull("author", layer.author.orNull)
                        addKeyAndStringIfNotNull("comment", layer.comment.orNull)
                        addKeyAndStringIfNotNull("created", layer.creationTime.orNull?.toString())
                        addKeyAndStringIfNotNull("created_by", layer.createdBy.orNull)
                        if (!layer.diffId.isPresent) {
                            addKey("empty_layer").addBoolean(true)
                        }
                    }
                }
            }
            addKey("os").addString(platform.os.get())
            addKeyAndArrayIfNotEmpty("os.features", platform.osFeatures.orNull)
            addKeyAndStringIfNotNull("os.version", platform.osVersion.orNull)
            addKey("rootfs").addObject {
                // sorted for canonical json: diff_ids, type
                addKey("diff_ids").addArray {
                    for (layer in layers) {
                        if (layer.diffId.isPresent) {
                            addString(layer.diffId.get())
                        }
                    }
                }
                addKey("type").addString("layers")
            }
            addKeyAndStringIfNotNull("variant", platform.variant.orNull)
        }.toByteArray()

        jsonFile.get().asFile.writeBytes(jsonBytes)
        digestFile.get().asFile.writeText(calculateSha256Digest(jsonBytes))
    }
}