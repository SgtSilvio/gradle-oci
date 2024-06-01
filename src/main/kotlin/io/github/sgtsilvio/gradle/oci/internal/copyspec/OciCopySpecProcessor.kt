package io.github.sgtsilvio.gradle.oci.internal.copyspec

import io.github.sgtsilvio.gradle.oci.internal.glob.GlobMatcher
import io.github.sgtsilvio.gradle.oci.internal.glob.convertGlobToRegex
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.ReproducibleFileVisitor
import java.io.OutputStream
import java.time.Instant
import java.util.*

private const val DEFAULT_FILE_PERMISSIONS = 0b110_100_100
private const val DEFAULT_DIRECTORY_PERMISSIONS = 0b111_101_101
private const val DEFAULT_USER_ID = 0L
private const val DEFAULT_GROUP_ID = 0L
private val DEFAULT_MODIFICATION_TIME: Instant = Instant.ofEpochSecond(1)

internal fun OciCopySpecInput.process(visitor: OciCopySpecVisitor) {
    val allFiles = HashMap<String, FileMetadata>()
    process(
        LinkedList(),
        "",
        emptyList(),
        emptyList(),
        filePermissions.orNull ?: DEFAULT_FILE_PERMISSIONS,
        directoryPermissions.orNull ?: DEFAULT_DIRECTORY_PERMISSIONS,
        emptyList(),
        userId.orNull ?: DEFAULT_USER_ID,
        emptyList(),
        groupId.orNull ?: DEFAULT_GROUP_ID,
        emptyList(),
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
    pendingDestinationPathStates: LinkedList<DestinationPathState>,
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
    val destinationPath = if (currentDestinationPath.isEmpty()) {
        parentDestinationPath
    } else {
        pendingDestinationPathStates += DestinationPathState(
            parentDestinationPath,
            currentDestinationPath,
            parentDirectoryPermissions,
            parentPermissionPatterns,
            parentUserId,
            parentUserIdPatterns,
            parentGroupId,
            parentGroupIdPatterns,
        )
        "$parentDestinationPath$currentDestinationPath/"
    }
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
    sources.visit(object : ReproducibleFileVisitor {
        override fun visitDir(dirDetails: FileVisitDetails) {
            visitDirectories(
                pendingDestinationPathStates,
                destinationPath,
                dirDetails.relativePath.segments,
                movePatterns,
                moveCache,
                directoryPermissions,
                permissionPatterns,
                userId,
                userIdPatterns,
                groupId,
                groupIdPatterns,
                visitor,
            )
        }

        override fun visitFile(fileDetails: FileVisitDetails) {
            val parentPath = visitDirectories(
                pendingDestinationPathStates,
                destinationPath,
                fileDetails.relativePath.parent.segments,
                movePatterns,
                moveCache,
                directoryPermissions,
                permissionPatterns,
                userId,
                userIdPatterns,
                groupId,
                groupIdPatterns,
                visitor,
            )
            val fileName = rename(parentPath, fileDetails.name, renamePatterns)
            val path = "$parentPath$fileName"
            val fileMetadata = FileMetadata(
                path,
                findMatch(permissionPatterns, path, filePermissions),
                findMatch(userIdPatterns, path, userId),
                findMatch(groupIdPatterns, path, groupId),
                DEFAULT_MODIFICATION_TIME,
                fileDetails.size,
            )
            visitor.visitFile(fileMetadata, FileSourceAdapter(fileDetails))
        }

        override fun isReproducibleFileOrder() = true
    })
    moveCache.clear()

    for (child in children) {
        child.process(
            pendingDestinationPathStates,
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
            visitor,
        )
    }
    if (pendingDestinationPathStates.isNotEmpty() && currentDestinationPath.isNotEmpty()) {
        pendingDestinationPathStates.removeLast()
    }
}

private class DestinationPathState(
    val destinationPath: String,
    val childDestinationPath: String,
    val permissions: Int,
    val permissionPatterns: List<Pair<GlobMatcher, Int>>,
    val userId: Long,
    val userIdPatterns: List<Pair<GlobMatcher, Long>>,
    val groupId: Long,
    val groupIdPatterns: List<Pair<GlobMatcher, Long>>,
)

private fun visitDirectories(
    pendingDestinationPathStates: LinkedList<DestinationPathState>,
    destinationPath: String,
    segments: Array<String>,
    movePatterns: List<Triple<GlobMatcher, Regex, String>>,
    moveCache: HashMap<String, String>,
    permissions: Int,
    permissionPatterns: List<Pair<GlobMatcher, Int>>,
    userId: Long,
    userIdPatterns: List<Pair<GlobMatcher, Long>>,
    groupId: Long,
    groupIdPatterns: List<Pair<GlobMatcher, Long>>,
    visitor: OciCopySpecVisitor,
): String {
    for (state in pendingDestinationPathStates) {
        visitDirectories(
            state.destinationPath,
            state.childDestinationPath,
            state.permissions,
            state.permissionPatterns,
            state.userId,
            state.userIdPatterns,
            state.groupId,
            state.groupIdPatterns,
            visitor,
        )
    }
    pendingDestinationPathStates.clear()

    var path = destinationPath
    for (segment in segments) {
        val pathBeforeMove = "$path$segment"
        val movedSegmentPath = moveCache[pathBeforeMove] ?: rename(path, segment, movePatterns, ::validateMove).also {
            visitDirectories(
                path,
                it,
                permissions,
                permissionPatterns,
                userId,
                userIdPatterns,
                groupId,
                groupIdPatterns,
                visitor,
            )
            moveCache[pathBeforeMove] = it
        }
        if (movedSegmentPath.isNotEmpty()) {
            path += "$movedSegmentPath/"
        }
    }
    return path
}

private fun visitDirectories(
    parentPath: String,
    directoryPath: String,
    permissions: Int,
    permissionPatterns: List<Pair<GlobMatcher, Int>>,
    userId: Long,
    userIdPatterns: List<Pair<GlobMatcher, Long>>,
    groupId: Long,
    groupIdPatterns: List<Pair<GlobMatcher, Long>>,
    visitor: OciCopySpecVisitor,
) {
    if (directoryPath.isNotEmpty()) {
        var path = parentPath
        for (directoryName in directoryPath.split('/')) {
            path = "$path$directoryName/"
            val fileMetadata = FileMetadata(
                path,
                findMatch(permissionPatterns, path, permissions),
                findMatch(userIdPatterns, path, userId),
                findMatch(groupIdPatterns, path, groupId),
                DEFAULT_MODIFICATION_TIME,
                0,
            )
            visitor.visitDirectory(fileMetadata)
        }
    }
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

private fun convertRenamePatterns(
    parentPatterns: List<Triple<GlobMatcher, Regex, String>>,
    patterns: List<Triple<String, String, String>>,
    destinationPath: String,
): List<Triple<GlobMatcher, Regex, String>> {
    if (parentPatterns.isEmpty() && patterns.isEmpty()) {
        return emptyList()
    }
    val convertedPatterns = LinkedList<Triple<GlobMatcher, Regex, String>>()
    for (parentPattern in parentPatterns) {
        if (parentPattern.first.matchesParentDirectory(destinationPath)) {
            convertedPatterns.add(parentPattern)
        }
    }
    for ((pathPattern, regex, replacement) in patterns) {
        val pathRegex = "^${convertGlobToRegex(pathPattern)}$"
        convertedPatterns.add(Triple(GlobMatcher(pathRegex, destinationPath.length), Regex("^$regex$"), replacement))
    }
    return convertedPatterns
}

private fun <T> convertPatterns(
    parentPatterns: List<Pair<GlobMatcher, T>>,
    patterns: List<Pair<String, T>>,
    destinationPath: String,
): List<Pair<GlobMatcher, T>> {
    if (parentPatterns.isEmpty() && patterns.isEmpty()) {
        return emptyList()
    }
    val convertedPatterns = LinkedList<Pair<GlobMatcher, T>>()
    for (parentPattern in parentPatterns) {
        if (parentPattern.first.matchesParentDirectory(destinationPath)) {
            convertedPatterns.add(parentPattern)
        }
    }
    for ((pathPattern, value) in patterns) {
        val pathRegex = "^${convertGlobToRegex(pathPattern)}$"
        convertedPatterns.add(Pair(GlobMatcher(pathRegex, destinationPath.length), value))
    }
    return convertedPatterns
}

private fun <T> findMatch(patterns: List<Pair<GlobMatcher, T>>, path: String, default: T): T {
    val match = patterns.findLast { it.first.matches(path) }
    return if (match == null) default else match.second
}

private class FileSourceAdapter(private val fileTreeElement: FileTreeElement) : FileSource {
    override fun copyTo(output: OutputStream) = fileTreeElement.copyTo(output)
}
