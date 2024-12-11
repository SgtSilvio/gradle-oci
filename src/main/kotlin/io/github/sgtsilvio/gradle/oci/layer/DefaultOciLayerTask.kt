package io.github.sgtsilvio.gradle.oci.layer

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.internal.copyspec.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.Action
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested

abstract class DefaultOciLayerTask : OciLayerTask() {

    private val _contents = project.objects.newOciCopySpec()

    @get:Nested
    protected val copySpecInput = _contents.asInput(project.providers)

    @get:Internal
    val contents: OciCopySpec get() = _contents

    fun contents(action: Action<in OciCopySpec>) = action.execute(_contents)

    override fun run(tos: TarArchiveOutputStream) {
        copySpecInput.get().process(object : OciCopySpecVisitor {
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

            private fun TarArchiveEntry.setPermissions(permissions: Int) {
                mode = (mode and 0b111_111_111.inv()) or (permissions and 0b111_111_111)
            }
        })
    }
}
