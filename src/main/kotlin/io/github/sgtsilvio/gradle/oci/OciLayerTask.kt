package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.copyspec.*
import io.github.sgtsilvio.gradle.oci.internal.formatSha256Digest
import io.github.sgtsilvio.gradle.oci.internal.newSha256MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.newInstance
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.util.zip.GZIPOutputStream

/**
 * @author Silvio Giebl
 */
abstract class OciLayerTask : DefaultTask() {

    private val _contents = project.objects.newInstance<OciCopySpecImpl>()

    @get:Nested
    protected val copySpecInput = _contents.asInput(project.providers)

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val tarFile = project.objects.fileProperty().convention(outputDirectory.file("layer.tar.gz"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("layer.digest"))

    @get:OutputFile
    val diffIdFile = project.objects.fileProperty().convention(outputDirectory.file("layer.diffid"))

    @get:Internal
    val contents: OciCopySpec get() = _contents

    fun contents(action: Action<in OciCopySpec>) = action.execute(_contents)

    @TaskAction
    protected fun run() {
        val copySpecInput = copySpecInput.get()
        val tarFile = tarFile.get().asFile
        val digestFile = digestFile.get().asFile
        val diffIdFile = diffIdFile.get().asFile

        FileOutputStream(tarFile).use { fos ->
            DigestOutputStream(fos, newSha256MessageDigest()).use { dos ->
                GZIPOutputStream(dos).use { gos ->
                    DigestOutputStream(gos, newSha256MessageDigest()).use { dos2 ->
                        TarArchiveOutputStream(dos2, StandardCharsets.UTF_8.name()).use { tos ->
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
                        diffIdFile.writeText(formatSha256Digest(dos2.messageDigest.digest()))
                    }
                }
                digestFile.writeText(formatSha256Digest(dos.messageDigest.digest()))
            }
        }
    }
}