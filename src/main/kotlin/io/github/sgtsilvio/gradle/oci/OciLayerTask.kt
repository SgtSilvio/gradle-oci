package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.FileVisitor
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.newInstance
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
    protected val rootCopySpec = project.objects.newInstance<OciCopySpecImpl>()

    @get:Internal
    val outputDirectory: DirectoryProperty = project.objects.directoryProperty()

    @get:OutputFile
    val tarFile = project.objects.fileProperty().convention(outputDirectory.file("layer.tar.gz"))

    @get:OutputFile
    val digestFile = project.objects.fileProperty().convention(outputDirectory.file("layer.digest"))

    @get:OutputFile
    val diffIdFile = project.objects.fileProperty().convention(outputDirectory.file("layer.diffid"))

    val contents: OciCopySpec get() = rootCopySpec

    fun contents(action: Action<OciCopySpec>) = action.execute(rootCopySpec)

    @TaskAction
    protected fun run() {
        val tarArchiveEntries = TreeMap<TarArchiveEntry, FileTreeElement>(Comparator.comparing { it.name })
        processCopySpec(rootCopySpec, "", listOf(), listOf(), listOf(), listOf(), tarArchiveEntries)

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

    private fun processCopySpec(
        copySpec: OciCopySpecImpl,
        parentDestinationPath: String,
        parentRenamePatterns: List<Pair<Regex, String>>,
        parentPermissionPatterns: List<Pair<Regex, Int?>>,
        parentUserIdPatterns: List<Pair<Regex, Long>>,
        parentGroupIdPatterns: List<Pair<Regex, Long>>,
        tarArchiveEntries: MutableMap<TarArchiveEntry, FileTreeElement>
    ) {
        // TODO put all path elements to tarArchiveEntries (to extra implicitTarArchiveDirectories)
        val destinationPath = parentDestinationPath + copySpec.destinationPath.get()
        val renamePatterns = parentRenamePatterns + convertRenamePatterns(copySpec.renamePatterns.orNull)
        val filePermissions = copySpec.filePermissions.orNull
        val directoryPermissions = copySpec.directoryPermissions.orNull
        val permissionPatterns = parentPermissionPatterns + convertPatterns(copySpec.permissionPatterns.orNull)
        val userId = copySpec.userId.get()
        val userIdPatterns = parentUserIdPatterns + convertPatterns(copySpec.userIdPatterns.orNull)
        val groupId = copySpec.groupId.get()
        val groupIdPatterns = parentGroupIdPatterns + convertPatterns(copySpec.groupIdPatterns.orNull)
        copySpec.sources.asFileTree.visit(object : FileVisitor {
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
                    defaultPermissions
                ) ?: fileVisitDetails.mode
                tarArchiveEntry.mode = (tarArchiveEntry.mode and 0b111_111_111.inv()) or permissions
                tarArchiveEntry.setUserId(findFirstMatch(userIdPatterns, tarArchiveEntry.name, userId))
                tarArchiveEntry.setGroupId(findFirstMatch(groupIdPatterns, tarArchiveEntry.name, groupId))
                tarArchiveEntry.setModTime(DEFAULT_MODIFICATION_TIME.toEpochMilli()) // TODO
                if (tarArchiveEntries.put(tarArchiveEntry, fileVisitDetails) != null) {
                    throw IllegalStateException("duplicate entry") // TODO
                }
            }
        })
        for (child in copySpec.children) {
            processCopySpec(
                child,
                destinationPath,
                renamePatterns,
                permissionPatterns,
                userIdPatterns,
                groupIdPatterns,
                tarArchiveEntries
            )
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
        // TODO default only if no match, null means null
    }
}