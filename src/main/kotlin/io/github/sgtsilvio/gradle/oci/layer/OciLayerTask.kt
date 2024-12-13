package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciDigestAlgorithm
import io.github.sgtsilvio.gradle.oci.metadata.calculateOciDigest
import io.github.sgtsilvio.gradle.oci.metadata.toOciDigest
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

/**
 * @author Silvio Giebl
 */
abstract class OciLayerTask : DefaultTask() {

    @get:Input
    val digestAlgorithm: Property<OciDigestAlgorithm> =
        project.objects.property<OciDigestAlgorithm>().convention(OciDigestAlgorithm.SHA_256)

    @get:Input
    val compression: Property<OciLayerCompression> =
        project.objects.property<OciLayerCompression>().convention(OciLayerCompression.GZIP)

    @get:Internal
    val mediaType: Provider<String> = compression.map { it.mediaType }

    @get:Internal
    val destinationDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:Internal
    val classifier = project.objects.property<String>()

    @get:Internal
    val extension: Property<String> = project.objects.property<String>().convention(compression.map { it.extension })

    @get:OutputFile
    val file: RegularFileProperty = project.objects.fileProperty()
        .convention(destinationDirectory.file(classifier.zip(extension) { classifier, ext -> "$classifier.$ext" }))

    @get:OutputFile
    protected val propertiesFile: RegularFileProperty =
        project.objects.fileProperty().convention(destinationDirectory.file(classifier.map { "$it.properties" }))

    @get:Internal
    val digest: Provider<OciDigest>

    @get:Internal
    val size: Provider<Long>

    @get:Internal
    val diffId: Provider<OciDigest>

    init {
        val properties = propertiesFile.map { Properties().apply { load(FileInputStream(it.asFile)) } }
        digest = properties.map { it.getProperty("digest").toOciDigest() }
        size = properties.map { it.getProperty("size").toLong() }
        diffId = properties.map { it.getProperty("diffId").toOciDigest() }
    }

    @TaskAction
    protected fun run() {
        val digestAlgorithm = digestAlgorithm.get()
        val compression = compression.get()
        val file = file.get().asFile
        val propertiesFile = propertiesFile.get().asFile

        val diffId: OciDigest
        val digest = FileOutputStream(file).calculateOciDigest(digestAlgorithm) { compressedDos ->
            diffId = compression.createOutputStream(compressedDos).calculateOciDigest(digestAlgorithm) { dos ->
                TarArchiveOutputStream(dos, Charsets.UTF_8.name()).use { tarOutputStream ->
                    tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tarOutputStream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    run(tarOutputStream)
                }
            }
        }
        propertiesFile.writeText("digest=$digest\nsize=${file.length()}\ndiffId=$diffId")
    }

    protected abstract fun run(tarOutputStream: TarArchiveOutputStream)
}
