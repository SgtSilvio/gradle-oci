package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.internal.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTreeElement
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
        val tarArchiveEntries = TreeMap<TarArchiveEntry, FileTreeElement>(Comparator.comparing { it.name })
        processCopySpec(
            rootCopySpec,
            "",
            listOf(),
            listOf(),
            DEFAULT_FILE_PERMISSIONS,
            DEFAULT_DIRECTORY_PERMISSIONS,
            listOf(),
            DEFAULT_USER_ID,
            listOf(),
            DEFAULT_GROUP_ID,
            listOf(),
            tarArchiveEntries
        )

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
                                    tarArchiveEntry.value.copyTo(tos) // TODO does not work for tars
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
        parentRenamePatterns: List<Triple<GlobMatcher, Regex, String>>,
        parentMovePatterns: List<Triple<GlobMatcher, Regex, String>>,
        parentFilePermissions: Int,
        parentDirectoryPermissions: Int,
        parentPermissionPatterns: List<Pair<GlobMatcher, Int>>,
        parentUserId: Long,
        parentUserIdPatterns: List<Pair<GlobMatcher, Long>>,
        parentGroupId: Long,
        parentGroupIdPatterns: List<Pair<GlobMatcher, Long>>,
        tarArchiveEntries: MutableMap<TarArchiveEntry, FileTreeElement>
    ) {
        // TODO put all path elements to tarArchiveEntries (to extra implicitTarArchiveDirectories)
        val currentDestinationPath = copySpec.destinationPath.get().ifNotEmpty { "$it/" }
        val destinationPath = parentDestinationPath + currentDestinationPath
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

        val moveEntries = HashMap<FileElement, String>()

        copySpec.sources.asFileTree.visit(object : ReproducibleFileVisitor {
            override fun visitDir(dirDetails: FileVisitDetails) {
                val path = move(destinationPath, dirDetails.relativePath.segments, movePatterns, moveEntries)
//                var path = ""
//                for (directoryName in directoryNames) {
//                    path = "$path$directoryName/"
                val tarArchiveEntry = TarArchiveEntry(path)
                visitEntry(tarArchiveEntry, directoryPermissions)
                if (tarArchiveEntries.put(tarArchiveEntry, dirDetails) != null) { // TODO dirDetails
                    throw IllegalStateException("duplicate entry") // TODO dir with same properties is ok
                }
//                }
            }

            override fun visitFile(fileDetails: FileVisitDetails) {
                val parentPath =
                    move(destinationPath, fileDetails.relativePath.parent.segments, movePatterns, moveEntries)
                val fileName = rename(parentPath, fileDetails.name, renamePatterns)
                val tarArchiveEntry = TarArchiveEntry("$parentPath$fileName")
                tarArchiveEntry.size = fileDetails.size
                visitEntry(tarArchiveEntry, filePermissions)
                if (tarArchiveEntries.put(tarArchiveEntry, fileDetails) != null) {
                    throw IllegalStateException("duplicate entry")
                }
            }

            private fun visitEntry(tarArchiveEntry: TarArchiveEntry, defaultPermissions: Int) {
                tarArchiveEntry.setPermissions(findMatch(permissionPatterns, tarArchiveEntry.name, defaultPermissions))
                tarArchiveEntry.setUserId(findMatch(userIdPatterns, tarArchiveEntry.name, userId))
                tarArchiveEntry.setGroupId(findMatch(groupIdPatterns, tarArchiveEntry.name, groupId))
                tarArchiveEntry.setModTime(DEFAULT_MODIFICATION_TIME.toEpochMilli()) // TODO
            }

            override fun isReproducibleFileOrder() = true
        })
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
                tarArchiveEntries
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
                    GlobMatcher("^$pathRegex$", destinationPath.length),
                    Regex("^${pattern.second}$"),
                    pattern.third
                )
            )
        }
        return convertedPatterns
    }

    private fun rename(
        parentPath: String,
        fileName: String,
        patterns: List<Triple<GlobMatcher, Regex, String>>
    ): String {
        var currentFileName = fileName
        for (pattern in patterns) {
            if (pattern.first.matches(parentPath)) {
                val renamedFileName = pattern.second.replaceFirst(currentFileName, pattern.third)
                currentFileName = validateRename(currentFileName, renamedFileName)
            }
        }
        return currentFileName
    }

    private fun validateRename(fileName: String, renamedFileName: String): String {
        if (renamedFileName.isEmpty()) {
            error("file name must not be empty after renaming ($fileName -> $renamedFileName)")
        } else if (renamedFileName.contains('/')) {
            error("file name must not contain '/' after renaming ($fileName -> $renamedFileName)")
        }
        return renamedFileName
    }

    private fun move(
        destinationPath: String,
        segments: Array<String>,
        patterns: List<Triple<GlobMatcher, Regex, String>>,
        moveEntries: HashMap<FileElement, String>
    ): String {
        var parentPath = destinationPath
        var parent: FileElement? = null
        for (directoryName in segments) {
            var fileElement = FileElement(parent, directoryName)
            val renamedDirectoryPath = moveEntries.compute(fileElement) { storedFileElement, storedPath ->
                if (storedPath != null) {
                    fileElement = storedFileElement
                    storedPath
                } else {
                    var currentDirectoryPath = directoryName
                    for (pattern in patterns) {
                        if (pattern.first.matches(parentPath)) {
                            val renamedDirectoryPath = pattern.second.replaceFirst(currentDirectoryPath, pattern.third)
                            currentDirectoryPath = validateMove(currentDirectoryPath, renamedDirectoryPath)
                        }
                    }
                    currentDirectoryPath
                }
            }
            parentPath += "$renamedDirectoryPath/"
            parent = fileElement
        }
        return parentPath
    }

    private fun validateMove(directoryName: String, renamedDirectoryPath: String): String {
        if (renamedDirectoryPath.startsWith('/')) {
            error("directory must not start with '/' after movement ($directoryName -> $renamedDirectoryPath)")
        } else if (renamedDirectoryPath.endsWith('/')) {
            error("directory must not end with '/' after movement ($directoryName -> $renamedDirectoryPath)")
        } else if (renamedDirectoryPath.contains("//")) {
            error("directory must not contain '//' after movement ($directoryName -> $renamedDirectoryPath)")
        }
        return renamedDirectoryPath
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

    fun TarArchiveEntry.setPermissions(permissions: Int) {
        mode = (mode and 0b111_111_111.inv()) or (permissions and 0b111_111_111)
    }

    inline fun String.ifNotEmpty(transformer: (String) -> String): String = if (isEmpty()) this else transformer(this)

    class FileElement(
        val parent: FileElement?,
        val name: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileElement) return false

            if (parent !== other.parent) return false
            if (name != other.name) return false
            return true
        }

        override fun hashCode(): Int {
            var result = System.identityHashCode(parent)
            result = 31 * result + name.hashCode()
            return result
        }
    }
}

// permissions, userId, groupId
// *        | file, file2                                        | ^[^/]$
// */       | dir/, dir2/                                        | ^[^/]/$
// test     | test                                               | ^test$
// test/    | test/                                              | ^test/$
// test/**  | test/, test/foo, test/bar, test/foo/, test/foo/bar | ^test/.*$
// test/**/ | test/foo/, test/foo/bar/                           | ^test/.*/$

// rename file (must not contain /)
// .*        | bar    | foo | bar
// .*        | $0.txt | foo | foo.txt
// (.*)o(.*) | $1a$2  | foo | fao

// rename dir (must contain / at the end, must NOT contain slashes between)


// into("foo/bar") { => immutable, not renamed
//     from("wab.txt")
//     rename(".*", "$0.aso") => foo/bar/**/.*
// }
// rename("foo/", "bar/", "test/")
//
//
//

//private fun rename(
//    destinationPath: String,
//    directoryPath: String,
//    fileName: String,
//    renamePatterns: List<Triple<Pattern, Pattern, String>>
//): Pair<String, String> {
//    var completeDirectoryPath = "$destinationPath$directoryPath"
//    var renamedFileName = fileName
//    for (renamePattern in renamePatterns) {
//        val matcher = renamePattern.first.matcher(completeDirectoryPath)
//        while (matcher.find()) {
//            val end = matcher.end()
//            println(end)
//            if (end < destinationPath.length) {
//                println("1")
//                continue
//            }
//            if (end < completeDirectoryPath.length) {
//                println("2 " + completeDirectoryPath[end - 1])
//                if (completeDirectoryPath[end - 1] == '/') {
//                    println("2.2")
//                    val nextSlash = completeDirectoryPath.indexOf('/', end)
//                    val directoryName = completeDirectoryPath.substring(end, nextSlash + 1)
//                    val renamedDirectoryName =
//                        renamePattern.second.matcher(directoryName).replaceAll(renamePattern.third)
//                    println(directoryName + " " + renamedDirectoryName)
//                    // TODO check if no change
//                    // TODO validation that still directory
//                    completeDirectoryPath = completeDirectoryPath.substring(0, end) +
//                            renamedDirectoryName +
//                            completeDirectoryPath.substring(nextSlash + 1)
//                }
//                continue
//            }
//            println("3")
//            renamedFileName = renamePattern.second.matcher(renamedFileName).replaceAll(renamePattern.third)
//            // TODO validation that still the same (file or directory)
//        }
//    }
//    return Pair(completeDirectoryPath, renamedFileName)
//}
//
//fun main() {
//    println(
//        rename(
//            "foo/",
//            "foo/bar/",
//            "wab",
//            listOf(
//                Triple(Pattern.compile('^' + convertToRegex("**/")), Pattern.compile("^.*$"), "$0.txt"),
////                Triple(Pattern.compile('^' + convertToRegex("foo/foo/bar/")), Pattern.compile("^.*$"), "$0.txt"),
//                Triple(Pattern.compile('^' + convertToRegex("**/")), Pattern.compile("^foo/$"), "ha/"),
//                Triple(Pattern.compile('^' + convertToRegex("foo/")), Pattern.compile("^foo/$"), "ha/"),
//            )
//        )
//    )
////    val matcher = Pattern.compile("^(.*?/)?").matcher("abc/def/")
//    val matcher = Pattern.compile("^(?=((.*/)?)).").matcher("abc/def/")
//    while (matcher.find()) {
//        println("${matcher.start()} ${matcher.end()}")
//    }
//}