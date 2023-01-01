package io.github.sgtsilvio.gradle.oci.internal

import org.gradle.api.file.FileTree
import org.gradle.api.tasks.*
import org.gradle.api.tasks.util.PatternSet

/**
 * @author Silvio Giebl
 */
class OciCopySpecInput(copySpec: OciCopySpecImpl, parentFilter: PatternSet? = null) {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources: FileTree

    @get:Input
    val destinationPath = copySpec.destinationPath

    @get:Input
    val renamePatterns = copySpec.renamePatterns

    @get:Input
    val movePatterns = copySpec.movePatterns

    @get:Input
    @get:Optional
    val filePermissions = copySpec.filePermissions

    @get:Input
    @get:Optional
    val directoryPermissions = copySpec.directoryPermissions

    @get:Input
    val permissionPatterns = copySpec.permissionPatterns

    @get:Input
    @get:Optional
    val userId = copySpec.userId

    @get:Input
    val userIdPatterns = copySpec.userIdPatterns

    @get:Input
    @get:Optional
    val groupId = copySpec.groupId

    @get:Input
    val groupIdPatterns = copySpec.groupIdPatterns

    @get:Nested
    val children: List<OciCopySpecInput>

    init {
        val filter = parentFilter + copySpec.filter
        sources = copySpec.sources.asFileTree.matching(filter)
        children = ArrayList<OciCopySpecInput>(copySpec.children.size).apply {
            for (child in copySpec.children) {
                add(OciCopySpecInput(child, filter))
            }
        }
    }
}

private operator fun PatternSet?.plus(other: PatternSet): PatternSet {
    if ((this == null) || isEmpty) {
        return other
    }
    val combined = PatternSet()
    combined.include(includes)
    combined.include(other.includes)
    combined.exclude(excludes)
    combined.exclude(other.excludes)
    combined.includeSpecs(includeSpecs)
    combined.includeSpecs(other.includeSpecs)
    combined.excludeSpecs(excludeSpecs)
    combined.excludeSpecs(other.excludeSpecs)
    return combined
}