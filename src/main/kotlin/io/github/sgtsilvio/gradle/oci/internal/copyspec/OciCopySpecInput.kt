package io.github.sgtsilvio.gradle.oci.internal.copyspec

import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.util.PatternSet

/**
 * @author Silvio Giebl
 */
class OciCopySpecInput(copySpec: OciCopySpecImpl, parentFilter: PatternSet?) {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources: FileTree

    @get:Input
    val destinationPath: Provider<String> = copySpec.destinationPath

    @get:Input
    val renamePatterns: List<Triple<String, String, String>> = copySpec.renamePatterns

    @get:Input
    val movePatterns: List<Triple<String, String, String>> = copySpec.movePatterns

    @get:Input
    @get:Optional
    val filePermissions: Provider<Int> = copySpec.filePermissions

    @get:Input
    @get:Optional
    val directoryPermissions: Provider<Int> = copySpec.directoryPermissions

    @get:Input
    val permissionPatterns: List<Pair<String, Int>> = copySpec.permissionPatterns

    @get:Input
    @get:Optional
    val userId: Provider<Long> = copySpec.userId

    @get:Input
    val userIdPatterns: List<Pair<String, Long>> = copySpec.userIdPatterns

    @get:Input
    @get:Optional
    val groupId: Provider<Long> = copySpec.groupId

    @get:Input
    val groupIdPatterns: List<Pair<String, Long>> = copySpec.groupIdPatterns

    @get:Nested
    val children: List<OciCopySpecInput>

    init {
        val filter = parentFilter + copySpec.filter
        sources = copySpec.sources.asFileTree.matching(filter)
        children = copySpec.children.map { OciCopySpecInput(it, filter) }
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
