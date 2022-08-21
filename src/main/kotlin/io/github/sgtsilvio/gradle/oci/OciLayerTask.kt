package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.ReproducibleFileVisitor
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

    @get:Internal
    val contents: OciCopySpec get() = rootCopySpec

    fun contents(action: Action<OciCopySpec>) = action.execute(rootCopySpec)

    @TaskAction
    protected fun run() {
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
                            processCopySpec(
                                rootCopySpec,
                                object : OciCopySpecVisitor {
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
                                }
                            )
                        }
                        diffIdFile.writeText(formatSha256Digest(dos2.messageDigest.digest()))
                    }
                }
                digestFile.writeText(formatSha256Digest(dos.messageDigest.digest()))
            }
        }
    }

    private fun TarArchiveEntry.setPermissions(permissions: Int) {
        mode = (mode and 0b111_111_111.inv()) or (permissions and 0b111_111_111)
    }

    private fun processCopySpec(copySpec: OciCopySpecImpl, visitor: OciCopySpecVisitor) {
        val allFiles = HashMap<String, FileMetadata>()
        processCopySpec(
            copySpec,
            "",
            listOf(),
            listOf(),
            copySpec.filePermissions.orNull ?: DEFAULT_FILE_PERMISSIONS,
            copySpec.directoryPermissions.orNull ?: DEFAULT_DIRECTORY_PERMISSIONS,
            listOf(),
            copySpec.userId.orNull ?: DEFAULT_USER_ID,
            listOf(),
            copySpec.groupId.orNull ?: DEFAULT_GROUP_ID,
            listOf(),
            object : OciCopySpecVisitor {
                override fun visitFile(fileMetadata: FileMetadata, fileSource: FileSource) {
                    if (allFiles.putIfAbsent(fileMetadata.path, fileMetadata) == null) {
                        visitor.visitFile(fileMetadata, fileSource)
                    } else {
                        throw IllegalStateException("duplicate file '${fileMetadata.path}'")
                    }
                }

                override fun visitDirectory(fileMetadata: FileMetadata) {
                    val previousFileMetadata = allFiles.putIfAbsent(fileMetadata.path, fileMetadata)
                    if (previousFileMetadata == null) {
                        visitor.visitDirectory(fileMetadata)
                    } else if (previousFileMetadata != fileMetadata) {
                        throw IllegalStateException(
                            "duplicate directory with different metadata ($previousFileMetadata vs $fileMetadata)"
                        )
                    }
                }
            }
        )
        allFiles.clear()
    }

    private fun processCopySpec(
        copySpec: OciCopySpecImpl,
        parentDestinationPath: String,
        parentRenamePatterns: List<Triple<GlobMatcher, Regex, String>>,
        parentMovePatterns: List<Triple<GlobMatcher, Regex, String>>,
        parentFilePermissions: Int,
        parentDirectoryPermissions: Int,
        parentPermissionPatterns: List<Pair<GlobMatcher, Int>>,
        parentUserId: Long,
        parentUserIdPatterns: List<Pair<GlobMatcher, Long>>,
        parentGroupId: Long,
        parentGroupIdPatterns: List<Pair<GlobMatcher, Long>>,
        visitor: OciCopySpecVisitor
    ) {
        val currentDestinationPath = copySpec.destinationPath.get()
        visitAllDirectories(parentDestinationPath, currentDestinationPath) { path ->
            val fileMetadata = FileMetadata(
                path,
                findMatch(parentPermissionPatterns, path, parentDirectoryPermissions),
                findMatch(parentUserIdPatterns, path, parentUserId),
                findMatch(parentGroupIdPatterns, path, parentGroupId),
                DEFAULT_MODIFICATION_TIME
            )
            visitor.visitDirectory(fileMetadata)
        }

        val destinationPath = parentDestinationPath + currentDestinationPath.addDirectorySlash()
        val renamePatterns = convertRenamePatterns(parentRenamePatterns, copySpec.renamePatterns.get(), destinationPath)
        val movePatterns = convertRenamePatterns(parentMovePatterns, copySpec.movePatterns.get(), destinationPath)
        val filePermissions = copySpec.filePermissions.orNull ?: parentFilePermissions
        val directoryPermissions = copySpec.directoryPermissions.orNull ?: parentDirectoryPermissions
        val permissionPatterns =
            convertPatterns(parentPermissionPatterns, copySpec.permissionPatterns.get(), destinationPath)
        val userId = copySpec.userId.orNull ?: parentUserId
        val userIdPatterns = convertPatterns(parentUserIdPatterns, copySpec.userIdPatterns.get(), destinationPath)
        val groupId = copySpec.groupId.orNull ?: parentGroupId
        val groupIdPatterns = convertPatterns(parentGroupIdPatterns, copySpec.groupIdPatterns.get(), destinationPath)

        val moveCache = HashMap<String, String>()
        copySpec.sources.asFileTree.visit(object : ReproducibleFileVisitor {
            override fun visitDir(dirDetails: FileVisitDetails) {
                move(destinationPath, dirDetails.relativePath.segments, movePatterns, moveCache) { path ->
                    val fileMetadata = FileMetadata(
                        path,
                        findMatch(permissionPatterns, path, directoryPermissions),
                        findMatch(userIdPatterns, path, userId),
                        findMatch(groupIdPatterns, path, groupId),
                        DEFAULT_MODIFICATION_TIME
                    )
                    visitor.visitDirectory(fileMetadata)
                }
            }

            override fun visitFile(fileDetails: FileVisitDetails) {
                val parentPath =
                    move(destinationPath, fileDetails.relativePath.parent.segments, movePatterns, moveCache) { path ->
                        val fileMetadata = FileMetadata(
                            path,
                            findMatch(permissionPatterns, path, directoryPermissions),
                            findMatch(userIdPatterns, path, userId),
                            findMatch(groupIdPatterns, path, groupId),
                            DEFAULT_MODIFICATION_TIME
                        )
                        visitor.visitDirectory(fileMetadata)
                    }
                val fileName = rename(parentPath, fileDetails.name, renamePatterns)
                val path = "$parentPath$fileName"
                val fileMetadata = FileMetadata(
                    path,
                    findMatch(permissionPatterns, path, filePermissions),
                    findMatch(userIdPatterns, path, userId),
                    findMatch(groupIdPatterns, path, groupId),
                    DEFAULT_MODIFICATION_TIME,
                    fileDetails.size
                )
                visitor.visitFile(fileMetadata, FileSourceAdapter(fileDetails))
            }

            override fun isReproducibleFileOrder() = true
        })
        moveCache.clear()

        for (child in copySpec.children) {
            processCopySpec(
                child,
                destinationPath,
                renamePatterns,
                movePatterns,
                filePermissions,
                directoryPermissions,
                permissionPatterns,
                userId,
                userIdPatterns,
                groupId,
                groupIdPatterns,
                visitor
            )
        }
    }

    private fun convertRenamePatterns(
        parentPatterns: List<Triple<GlobMatcher, Regex, String>>,
        patterns: List<Triple<String, String, String>>,
        destinationPath: String
    ): List<Triple<GlobMatcher, Regex, String>> {
        if (parentPatterns.isEmpty() && patterns.isEmpty()) {
            return listOf()
        }
        val convertedPatterns = LinkedList<Triple<GlobMatcher, Regex, String>>()
        for (parentPattern in parentPatterns) {
            if (parentPattern.first.matchesParentDirectory(destinationPath)) {
                convertedPatterns.add(parentPattern)
            }
        }
        for (pattern in patterns) {
            val pathRegex = convertToRegex(pattern.first)
            convertedPatterns.add(
                Triple(
                    GlobMatcher("^$pathRegex$", destinationPath.length), Regex("^${pattern.second}$"), pattern.third
                )
            )
        }
        return convertedPatterns
    }

    private inline fun rename(
        parentPath: String,
        fileName: String,
        patterns: List<Triple<GlobMatcher, Regex, String>>,
        validation: (String, String) -> String
    ): String {
        var renamedFileName = fileName
        for (pattern in patterns) {
            if (pattern.first.matches(parentPath)) {
                val newRenamedFileName = pattern.second.replaceFirst(renamedFileName, pattern.third)
                renamedFileName = validation.invoke(renamedFileName, newRenamedFileName)
            }
        }
        return renamedFileName
    }

    private fun rename(parentPath: String, fileName: String, patterns: List<Triple<GlobMatcher, Regex, String>>) =
        rename(parentPath, fileName, patterns, ::validateRename)

    private fun validateRename(fileName: String, renamedFileName: String): String {
        if (renamedFileName.isEmpty()) {
            error("file name must not be empty after renaming ($fileName -> $renamedFileName)")
        } else if (renamedFileName.contains('/')) {
            error("file name must not contain '/' after renaming ($fileName -> $renamedFileName)")
        }
        return renamedFileName
    }

    private inline fun move(
        destinationPath: String,
        segments: Array<String>,
        patterns: List<Triple<GlobMatcher, Regex, String>>,
        moveCache: HashMap<String, String>,
        crossinline newDirectoryAction: (String) -> Unit
    ): String {
        var movedPath = destinationPath
        for (directoryName in segments) {
            val parentPath = movedPath
            val movedDirectoryPath = moveCache.computeIfAbsent("$parentPath/$directoryName") {
                val movedDirectoryPath = rename(parentPath, directoryName, patterns, ::validateMove)
                visitAllDirectories(parentPath, movedDirectoryPath, newDirectoryAction)
                movedDirectoryPath
            }
            movedPath += movedDirectoryPath.addDirectorySlash()
        }
        return movedPath
    }

    private fun validateMove(directoryName: String, movedDirectoryPath: String): String {
        if (movedDirectoryPath.startsWith('/')) {
            error("directory must not start with '/' after movement ($directoryName -> $movedDirectoryPath)")
        } else if (movedDirectoryPath.endsWith('/')) {
            error("directory must not end with '/' after movement ($directoryName -> $movedDirectoryPath)")
        } else if (movedDirectoryPath.contains("//")) {
            error("directory must not contain '//' after movement ($directoryName -> $movedDirectoryPath)")
        }
        return movedDirectoryPath
    }

    private inline fun visitAllDirectories(parentPath: String, directoryPath: String, visitor: (String) -> Unit) {
        if (directoryPath.isNotEmpty()) {
            var path = parentPath
            for (directoryName in directoryPath.split('/')) {
                path = "$path$directoryName/"
                visitor.invoke(path)
            }
        }
    }

    private fun <T> convertPatterns(
        parentPatterns: List<Pair<GlobMatcher, T>>,
        patterns: List<Pair<String, T>>,
        destinationPath: String
    ): List<Pair<GlobMatcher, T>> {
        if (parentPatterns.isEmpty() && patterns.isEmpty()) {
            return listOf()
        }
        val convertedPatterns = LinkedList<Pair<GlobMatcher, T>>()
        for (parentPattern in parentPatterns) {
            if (parentPattern.first.matchesParentDirectory(destinationPath)) {
                convertedPatterns.add(parentPattern)
            }
        }
        for (pattern in patterns) {
            val pathRegex = convertToRegex(pattern.first)
            convertedPatterns.add(Pair(GlobMatcher("^$pathRegex$", destinationPath.length), pattern.second))
        }
        return convertedPatterns
    }

    private fun <T> findMatch(patterns: List<Pair<GlobMatcher, T>>, path: String, default: T): T {
        val match = patterns.findLast { it.first.matches(path) }
        return if (match == null) default else match.second
    }

    private fun String.addDirectorySlash(): String = if (isEmpty()) "" else "$this/"

    interface OciCopySpecVisitor {
        fun visitFile(fileMetadata: FileMetadata, fileSource: FileSource)
        fun visitDirectory(fileMetadata: FileMetadata)
    }
}