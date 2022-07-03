package io.github.sgtsilvio.gradle.oci

import com.google.cloud.tools.jib.hash.CountingDigestOutputStream
import io.github.sgtsilvio.gradle.oci.internal.addKeyAndArray
import io.github.sgtsilvio.gradle.oci.internal.addKeyAndObject
import io.github.sgtsilvio.gradle.oci.internal.addKeyAndValue
import io.github.sgtsilvio.gradle.oci.internal.jsonStringBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
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
    val volumes = project.objects.listProperty<String>()

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

    // TODO history (nested (created, author, created_by, comment, empty_layer), optional)

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val jsonFile = project.objects.fileProperty().convention(outputDirectory.file("config.json"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("config.digest"))

    @TaskAction
    protected fun run() {
        val creationTime = creationTime.map { it.toString() }.orNull
        val author = author.orNull
        val platformArchitecture = platform.architecture.get()
        val platformOs = platform.os.get()
        val platformOsVersion = platform.osVersion.orNull
        val platformOsFeatures = platform.osFeatures.orNull
        val platformVariant = platform.variant.orNull
        val user = user.orNull
        val ports = ports.orNull
        val environment = environment.orNull
        val entryPoint = entryPoint.orNull
        val arguments = arguments.orNull
        val volumes = volumes.orNull
        val workingDirectory = workingDirectory.orNull
        val labels = labels.orNull
        val stopSignal = stopSignal.orNull
        val layerDiffIds = layerDiffIds.get()
        val jsonFile = jsonFile.get().asFile
        val digestFile = digestFile.get().asFile

        val jsonStringBuilder = jsonStringBuilder()
        jsonStringBuilder.addObject { rootObject ->
            // sorted for canonical json: architecture, author, config, created, history, os, os.features, os.version, rootfs, variant
            rootObject.addKey("architecture").addValue(platformArchitecture)
            rootObject.addKeyAndValue("author", author)
            rootObject.addKey("config").addObject { configObject ->
                // sorted for canonical json: Cmd, Entrypoint, Env, ExposedPorts, Labels, StopSignal, User, Volumes, WorkingDir
                configObject.addKeyAndArray("Cmd", arguments)
                configObject.addKeyAndArray("Entrypoint", entryPoint)
//                configObject.addKeyAndArray("Env", environment) // TODO
//                configObject.addKeyAndArray("ExposedPorts", ports) // TODO
                configObject.addKeyAndObject("Labels", labels)
                configObject.addKeyAndValue("StopSignal", stopSignal)
                configObject.addKeyAndValue("User", user)
//                configObject.addKeyAndObject("Volumes", volumes) // TODO
                configObject.addKeyAndValue("WorkingDir", workingDirectory)
            }
            rootObject.addKeyAndValue("created", creationTime)
            rootObject.addKey("history").addArray { historyArray -> // TODO optional
                // sorted for canonical json: author, comment, created, created_by, empty_layer
                // TODO
            }
            rootObject.addKeyAndValue("os", platformOs)
            rootObject.addKeyAndArray("os.features", platformOsFeatures)
            rootObject.addKeyAndValue("os.version", platformOsVersion)
            rootObject.addKey("rootfs").addObject { rootfsObject ->
                // sorted for canonical json: diff_ids, type
                rootfsObject.addKeyAndArray("diff_ids", layerDiffIds) // TODO required
                rootfsObject.addKey("type").addValue("layers")
            }
            rootObject.addKeyAndValue("variant", platformVariant)
        }

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