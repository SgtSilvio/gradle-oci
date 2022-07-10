package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.DEFAULT_MODIFICATION_TIME
import io.github.sgtsilvio.gradle.oci.internal.formatSha256Digest
import io.github.sgtsilvio.gradle.oci.internal.newSha256MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.util.*
import java.util.zip.GZIPOutputStream

/**
 * @author Silvio Giebl
 */
abstract class OciLayerTask : DefaultTask() {

    @get:Nested
//    val filesEntries: List<FilesEntry> = LinkedList()
    val filesEntries: MutableList<FilesEntry> = LinkedList()

    interface FilesEntry {

        @get:InputFiles
        val files: ConfigurableFileCollection

        @get:Input
        val destinationPath: Property<String>

        @get:Input
        val permissions: Property<Int>

        @get:Input
        val userId: Property<Long>

        @get:Input
        val groupId: Property<Long>
    }

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val tarFile = project.objects.fileProperty().convention(outputDirectory.file("layer.tar.gz"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("layer.digest"))

    @get:OutputFile
    val diffIdFile = project.objects.fileProperty().convention(outputDirectory.file("layer.diffid"))

    @TaskAction
    protected fun run() {
        val tarArchiveEntries = TreeMap<TarArchiveEntry, FileTreeElement>(Comparator.comparing { it.name })
        for (filesEntry in filesEntries) {
            val destinationPath = filesEntry.destinationPath.get()
            val permissions = filesEntry.permissions.get()
            val userId = filesEntry.userId.get()
            val groupId = filesEntry.groupId.get()
            filesEntry.files.asFileTree.visit(object : FileVisitor {
                override fun visitDir(dirDetails: FileVisitDetails) {
                    val tarArchiveEntry = TarArchiveEntry("$destinationPath/${dirDetails.relativePath}/")
                    visitEntry(tarArchiveEntry, dirDetails)
                }

                override fun visitFile(fileDetails: FileVisitDetails) {
                    val tarArchiveEntry = TarArchiveEntry("$destinationPath/${fileDetails.relativePath}")
                    tarArchiveEntry.size = fileDetails.size
                    visitEntry(tarArchiveEntry, fileDetails)
                }

                private fun visitEntry(tarArchiveEntry: TarArchiveEntry, fileVisitDetails: FileVisitDetails) {
                    tarArchiveEntry.mode = (tarArchiveEntry.mode and 0b111_111_111.inv()) or permissions // TODO
                    tarArchiveEntry.setUserId(userId) // TODO
                    tarArchiveEntry.setGroupId(groupId) // TODO
                    tarArchiveEntry.setModTime(DEFAULT_MODIFICATION_TIME.toEpochMilli()) // TODO
                    if (tarArchiveEntries.put(tarArchiveEntry, fileVisitDetails) != null) {
                        throw IllegalStateException("duplicate entry") // TODO
                    }
                }
            })
        }

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
                            for (tarArchiveEntry in tarArchiveEntries) {
                                tos.putArchiveEntry(tarArchiveEntry.key)
                                if (!tarArchiveEntry.value.isDirectory) {
                                    tarArchiveEntry.value.copyTo(tos)
                                }
                                tos.closeArchiveEntry()
                            }
                        }
                        diffIdFile.writeText(formatSha256Digest(dos2.messageDigest.digest()))
                    }
                }
                digestFile.writeText(formatSha256Digest(dos.messageDigest.digest()))
            }
        }
    }
}