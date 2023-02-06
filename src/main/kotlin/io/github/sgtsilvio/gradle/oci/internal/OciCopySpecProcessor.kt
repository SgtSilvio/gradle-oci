package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.internal.glob.GlobMatcher
import io.github.sgtsilvio.gradle.oci.internal.glob.convertToRegex
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.ReproducibleFileVisitor
import java.io.OutputStream
import java.util.*

const val DEFAULT_FILE_PERMISSIONS = 0b110_100_100
const val DEFAULT_DIRECTORY_PERMISSIONS = 0b111_101_101
const val DEFAULT_USER_ID = 0L
const val DEFAULT_GROUP_ID = 0L

fun OciCopySpecInput.process(visitor: OciCopySpecVisitor) {
    val allFiles = HashMap<String, FileMetadata>()
    process(
        "",
        listOf(),
        listOf(),
        filePermissions.orNull ?: DEFAULT_FILE_PERMISSIONS,
        directoryPermissions.orNull ?: DEFAULT_DIRECTORY_PERMISSIONS,
        listOf(),
        userId.orNull ?: DEFAULT_USER_ID,
        listOf(),
        groupId.orNull ?: DEFAULT_GROUP_ID,
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
        },
    )
    allFiles.clear()
}

private fun OciCopySpecInput.process(
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
    visitor: OciCopySpecVisitor,
) {
    val currentDestinationPath = destinationPath.get()
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
    val renamePatterns = convertRenamePatterns(parentRenamePatterns, renamePatterns, destinationPath)
    val movePatterns = convertRenamePatterns(parentMovePatterns, movePatterns, destinationPath)
    val filePermissions = filePermissions.orNull ?: parentFilePermissions
    val directoryPermissions = directoryPermissions.orNull ?: parentDirectoryPermissions
    val permissionPatterns = convertPatterns(parentPermissionPatterns, permissionPatterns, destinationPath)
    val userId = userId.orNull ?: parentUserId
    val userIdPatterns = convertPatterns(parentUserIdPatterns, userIdPatterns, destinationPath)
    val groupId = groupId.orNull ?: parentGroupId
    val groupIdPatterns = convertPatterns(parentGroupIdPatterns, groupIdPatterns, destinationPath)

    val moveCache = HashMap<String, String>()
    sources.asFileTree.visit(object : ReproducibleFileVisitor {
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

    for (child in children) {
        child.process(
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
    destinationPath: String,
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
                GlobMatcher("^$pathRegex$", destinationPath.length),
                Regex("^${pattern.second}$"),
                pattern.third,
            )
        )
    }
    return convertedPatterns
}

private inline fun rename(
    parentPath: String,
    fileName: String,
    patterns: List<Triple<GlobMatcher, Regex, String>>,
    validate: (String, String) -> String,
): String {
    var renamedFileName = fileName
    for (pattern in patterns) {
        if (pattern.first.matches(parentPath)) {
            val newRenamedFileName = pattern.second.replaceFirst(renamedFileName, pattern.third)
            renamedFileName = validate(renamedFileName, newRenamedFileName)
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
    crossinline newDirectoryAction: (String) -> Unit,
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
            visitor(path)
        }
    }
}

private fun <T> convertPatterns(
    parentPatterns: List<Pair<GlobMatcher, T>>,
    patterns: List<Pair<String, T>>,
    destinationPath: String,
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

private fun String.addDirectorySlash() = if (isEmpty()) "" else "$this/"

private class FileSourceAdapter(private val fileTreeElement: FileTreeElement) : FileSource {
    override fun asFile() = fileTreeElement.file
    override fun copyTo(output: OutputStream) = fileTreeElement.copyTo(output)
}