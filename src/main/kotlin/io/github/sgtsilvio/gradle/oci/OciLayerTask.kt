package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.copyspec.*
import io.github.sgtsilvio.gradle.oci.metadata.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.GZIPOutputStream

/**
 * @author Silvio Giebl
 */
abstract class OciLayerTask : DefaultTask() {

    private val _contents = project.objects.newInstance<OciCopySpecImpl>()

    @get:Nested
    protected val copySpecInput = _contents.asInput(project.providers)

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

    @get:Internal
    val contents: OciCopySpec get() = _contents

    fun contents(action: Action<in OciCopySpec>) = action.execute(_contents)

    @TaskAction
    protected fun run() {
        val copySpecInput = copySpecInput.get()
        val digestAlgorithm = digestAlgorithm.get()
        val compression = compression.get()
        val file = file.get().asFile
        val propertiesFile = propertiesFile.get().asFile

        val diffId: OciDigest
        val digest = FileOutputStream(file).calculateOciDigest(digestAlgorithm) { compressedDos ->
            diffId = compression.createOutputStream(compressedDos).calculateOciDigest(digestAlgorithm) { dos ->
                TarArchiveOutputStream(dos, StandardCharsets.UTF_8.name()).use { tos ->
                    tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    copySpecInput.process(object : OciCopySpecVisitor {
                        override fun visitFile(fileMetadata: FileMetadata, fileSource: FileSource) {
                            tos.putArchiveEntry(TarArchiveEntry(fileMetadata.path).apply {
                                setPermissions(fileMetadata.permissions)
                                setUserId(fileMetadata.userId)
                                setGroupId(fileMetadata.groupId)
                                setModTime(fileMetadata.modificationTime.toEpochMilli())
                                size = fileMetadata.size
                            })
                            fileSource.copyTo(tos)
                            tos.closeArchiveEntry()
                        }

                        override fun visitDirectory(fileMetadata: FileMetadata) {
                            tos.putArchiveEntry(TarArchiveEntry(fileMetadata.path).apply {
                                setPermissions(fileMetadata.permissions)
                                setUserId(fileMetadata.userId)
                                setGroupId(fileMetadata.groupId)
                                setModTime(fileMetadata.modificationTime.toEpochMilli())
                            })
                            tos.closeArchiveEntry()
                        }

                        private fun TarArchiveEntry.setPermissions(permissions: Int) {
                            mode = (mode and 0b111_111_111.inv()) or (permissions and 0b111_111_111)
                        }
                    })
                }
            }
        }
        propertiesFile.writeText("digest=$digest\nsize=${file.length()}\ndiffId=$diffId")
    }
}

enum class OciLayerCompression(internal val extension: String, internal val mediaType: String) {
    NONE("tar", UNCOMPRESSED_LAYER_MEDIA_TYPE) {
        override fun createOutputStream(out: OutputStream) = out
    },
    GZIP("tgz", GZIP_COMPRESSED_LAYER_MEDIA_TYPE) {
        override fun createOutputStream(out: OutputStream) = GZIPOutputStream(out)
    };

    internal abstract fun createOutputStream(out: OutputStream): OutputStream
}
