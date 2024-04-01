package io.github.sgtsilvio.gradle.oci.internal.copyspec

import java.io.OutputStream
import java.time.Instant

/**
 * @author Silvio Giebl
 */
internal interface OciCopySpecVisitor {
    fun visitFile(fileMetadata: FileMetadata, fileSource: FileSource)
    fun visitDirectory(fileMetadata: FileMetadata)
}

internal data class FileMetadata(
    val path: String,
    val permissions: Int,
    val userId: Long,
    val groupId: Long,
    val modificationTime: Instant,
    val size: Long,
)

internal interface FileSource {
    fun copyTo(output: OutputStream)
}
