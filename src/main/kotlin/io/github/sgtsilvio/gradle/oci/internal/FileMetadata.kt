package io.github.sgtsilvio.gradle.oci.internal

import org.gradle.api.file.FileTreeElement
import java.io.File
import java.io.OutputStream
import java.time.Instant

/**
 * @author Silvio Giebl
 */
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

class FileSourceAdapter(private val fileTreeElement: FileTreeElement) : FileSource {
    override fun asFile() = fileTreeElement.file

    override fun copyTo(output: OutputStream) {
        fileTreeElement.copyTo(output)
    }
}