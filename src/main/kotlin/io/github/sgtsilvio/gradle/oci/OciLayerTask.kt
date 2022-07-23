package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.DEFAULT_MODIFICATION_TIME
import io.github.sgtsilvio.gradle.oci.internal.convertToRegex
import io.github.sgtsilvio.gradle.oci.internal.formatSha256Digest
import io.github.sgtsilvio.gradle.oci.internal.newSha256MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
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
        val destinationPath: Property<String> // TODO convention ""

        @get:Input
        @get:Optional
        val renamePatterns: ListProperty<Triple<String, String, String>>

        @get:Input
        @get:Optional
        val filePermissions: Property<Int> // TODO convention 644

        @get:Input
        @get:Optional
        val directoryPermissions: Property<Int> // TODO convention 755

        @get:Input
        @get:Optional
        val permissionPatterns: ListProperty<Pair<String, Int>>

        @get:Input
        val userId: Property<Long> // TODO convention 0

        @get:Input
        @get:Optional
        val userIdPatterns: ListProperty<Pair<String, Long>>

        @get:Input
        val groupId: Property<Long> // TODO convention 0

        @get:Input
        @get:Optional
        val groupIdPatterns: ListProperty<Pair<String, Long>>
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
            val destinationPath =
                filesEntry.destinationPath.get() // TODO put all path elements to tarArchiveEntries (to extra implicitTarArchiveDirectories)
            val renamePatterns = convertRenamePatterns(filesEntry.renamePatterns.orNull)
            val filePermissions = filesEntry.filePermissions.orNull
            val directoryPermissions = filesEntry.directoryPermissions.orNull
            val permissionPatterns = convertPatterns(filesEntry.permissionPatterns.orNull)
            val userId = filesEntry.userId.get()
            val userIdPatterns = convertPatterns(filesEntry.userIdPatterns.orNull)
            val groupId = filesEntry.groupId.get()
            val groupIdPatterns = convertPatterns(filesEntry.groupIdPatterns.orNull)
            filesEntry.files.asFileTree.visit(object : FileVisitor {
                override fun visitDir(dirDetails: FileVisitDetails) {
                    val tarArchiveEntry =
                        TarArchiveEntry(rename("$destinationPath/${dirDetails.relativePath}/", renamePatterns))
                    visitEntry(tarArchiveEntry, dirDetails, directoryPermissions)
                }

                override fun visitFile(fileDetails: FileVisitDetails) {
                    val tarArchiveEntry =
                        TarArchiveEntry(rename("$destinationPath/${fileDetails.relativePath}", renamePatterns))
                    tarArchiveEntry.size = fileDetails.size
                    visitEntry(tarArchiveEntry, fileDetails, filePermissions)
                }

                private fun visitEntry(
                    tarArchiveEntry: TarArchiveEntry,
                    fileVisitDetails: FileVisitDetails,
                    defaultPermissions: Int?
                ) {
                    val permissions = findFirstMatch(
                        permissionPatterns,
                        tarArchiveEntry.name,
                        defaultPermissions ?: fileVisitDetails.mode
                    )
                    tarArchiveEntry.mode = (tarArchiveEntry.mode and 0b111_111_111.inv()) or permissions
                    tarArchiveEntry.setUserId(findFirstMatch(userIdPatterns, tarArchiveEntry.name, userId))
                    tarArchiveEntry.setGroupId(findFirstMatch(groupIdPatterns, tarArchiveEntry.name, groupId))
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

    private fun convertRenamePatterns(renamePatterns: List<Triple<String, String, String>>?): List<Pair<Regex, String>> {
        if (renamePatterns == null) {
            return listOf()
        }
        val convertedPatterns = LinkedList<Pair<Regex, String>>()
        for (renamePattern in renamePatterns) {
            // TODO check if pattern.first is interesting at all
            val pathRegex = convertToRegex(renamePattern.first)
            val fileNameRegex = renamePattern.second
            val regex = "(?<=^$pathRegex)" + if (fileNameRegex.endsWith('/')) {
                "${fileNameRegex.substring(0, fileNameRegex.length - 1)}(?=/)"
            } else {
                "$fileNameRegex$"
            }
            convertedPatterns.add(Pair(regex.toRegex(), renamePattern.third))
        }
        return convertedPatterns
    }

    private fun rename(path: String, renamePatterns: List<Pair<Regex, String>>): String {
        var renamedPath = path
        for (renamePattern in renamePatterns) {
            renamedPath = renamePattern.first.replace(renamedPath, renamePattern.second)
        }
        return renamedPath
    }

    private fun <T> convertPatterns(patterns: List<Pair<String, T>>?): List<Pair<Regex, T>> {
        if (patterns == null) {
            return listOf()
        }
        val convertedPatterns = LinkedList<Pair<Regex, T>>()
        for (pattern in patterns) {
            // TODO check if pattern.first is interesting at all // true if starts with **, TODO
            convertedPatterns.addFirst(Pair("^${convertToRegex(pattern.first)}$".toRegex(), pattern.second))
        }
        return convertedPatterns
    }

    private fun <T> findFirstMatch(patterns: List<Pair<Regex, T>>, path: String, default: T): T {
        return patterns.find { it.first.matches(path) }?.second ?: default
    }
}