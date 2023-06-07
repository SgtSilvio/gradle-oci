package io.github.sgtsilvio.gradle.oci.internal.glob

import java.util.regex.Pattern

/**
 * @author Silvio Giebl
 */
class GlobMatcher(regexString: String, private val startIndex: Int) {

    private val pattern: Pattern = Pattern.compile(regexString)

    fun matches(path: String) = pattern.matcher(path).region(startIndex, path.length).matches()

    fun matchesParentDirectory(parentDirectoryPath: String): Boolean {
        val matcher = pattern.matcher(parentDirectoryPath).region(startIndex, parentDirectoryPath.length)
        return matcher.matches() || matcher.hitEnd()
    }

    override fun toString() = "GlobMatcher(pattern=$pattern, startIndex=$startIndex)"
}