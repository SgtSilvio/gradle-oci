package io.github.sgtsilvio.gradle.oci

import com.google.cloud.tools.jib.hash.CountingDigestOutputStream
import io.github.sgtsilvio.gradle.oci.internal.addArray
import io.github.sgtsilvio.gradle.oci.internal.addObject
import io.github.sgtsilvio.gradle.oci.internal.jsonStringBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import java.io.FileOutputStream
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
    val platform = project.objects.newInstance(OciPlatform::class)

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
            author.orNull?.let {
                rootObject.addKey("author").addValue(it)
            }
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                arguments.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("Cmd").addArray(it)
                }
                entryPoint.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("Entrypoint").addArray(it)
                }
                environment.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("Env").addArray { envArray ->
                        it.forEach { (key, value) -> envArray.addValue("$key=$value") }
                    }
                }
                ports.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("ExposedPorts").addObject(it)
                }
                labels.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("Labels").addObject(it)
                }
                stopSignal.orNull?.let {
                    configObject.addKey("StopSignal").addValue(it)
                }
                user.orNull?.let {
                    configObject.addKey("User").addValue(it)
                }
                volumes.orNull?.takeIf { it.isNotEmpty() }?.let {
                    configObject.addKey("Volumes").addObject(it)
                }
                workingDirectory.orNull?.let {
                    configObject.addKey("WorkingDir").addValue(it)
                }
            }
            creationTime.orNull?.let {
                rootObject.addKey("created").addValue(it.toString())
            }
            history.orNull?.takeIf { it.isNotEmpty() }?.let { history ->
                rootObject.addKey("history").addArray { historyArray ->
                    history.forEach { historyEntry ->
                        historyArray.addObject { historyEntryObject ->
                            // sorted for canonical json: author, comment, created, created_by, empty_layer
                            historyEntry.author.orNull?.let {
                                historyEntryObject.addKey("author").addValue(it)
                            }
                            historyEntry.comment.orNull?.let {
                                historyEntryObject.addKey("author").addValue(it)
                            }
                            historyEntry.creationTime.orNull?.let {
                                historyEntryObject.addKey("created").addValue(it.toString())
                            }
                            historyEntry.createdBy.orNull?.let {
                                historyEntryObject.addKey("created_by").addValue(it)
                            }
                            historyEntry.emptyLayer.orNull?.let {
                                historyEntryObject.addKey("created_by").addValue(it)
                            }
                        }
                    }
                }
            }
            rootObject.addKey("os").addValue(platform.os.get())
            platform.osFeatures.orNull?.takeIf { it.isNotEmpty() }?.let {
                rootObject.addKey("os.features").addArray(it)
            }
            platform.osVersion.orNull?.let {
                rootObject.addKey("os.version").addValue(it)
            }
            rootObject.addKey("rootfs").addObject { rootfsObject ->
                // sorted for canonical json: diff_ids, type
                rootfsObject.addKey("diff_ids").addArray(layerDiffIds.get())
                rootfsObject.addKey("type").addValue("layers")
            }
            platform.variant.orNull?.let {
                rootObject.addKey("variant").addValue(it)
            }
        }

        val jsonFile = jsonFile.get().asFile
        val digestFile = digestFile.get().asFile

        val digest = FileOutputStream(jsonFile).use { fos ->
            CountingDigestOutputStream(fos).use { dos ->
                dos.write(jsonStringBuilder.toString().toByteArray())
                dos.flush()
                dos.computeDigest().digest.toString()
            }
        }

        digestFile.writeText(digest)
    }
}