package io.github.sgtsilvio.gradle.oci.internal.copyspec

import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * @author Silvio Giebl
 */
interface OciCopySpecVisitor {
    fun visitFile(fileMetadata: FileMetadata, fileSource: FileSource)
    fun visitDirectory(fileMetadata: FileMetadata)
}

data class FileMetadata(
    val path: String,
    val permissions: Int,
    val userId: Long,
    val groupId: Long,
    val modificationTime: Instant,
    val size: Long = 0,
)

interface FileSource {
    fun asFile(): File
    fun copyTo(output: OutputStream)
}