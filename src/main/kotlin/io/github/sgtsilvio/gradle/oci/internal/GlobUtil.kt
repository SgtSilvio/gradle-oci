package io.github.sgtsilvio.gradle.oci.internal

import java.util.regex.PatternSyntaxException

private const val REGEX_META_CHARS = "\\^$.|?*+()[{"

private fun isRegexMeta(c: Char) = REGEX_META_CHARS.contains(c)

fun convertToRegex(globPattern: String): String {
    val regex = StringBuilder()
    var i = 0
    while (i < globPattern.length) {
        when (val c = globPattern[i++]) {
            '*' -> {
                if ((i < globPattern.length) && (globPattern[i] == '*')) {
                    i++
                    if ((i < globPattern.length) && (globPattern[i] == '*')) {
                        throw PatternSyntaxException("'***' not allowed", globPattern, i)
                    }
                    if (// last char is either '/' or string start
                        ((i < 3) || (globPattern[i - 3] == '/')) &&
                        // next char is '/'
                        (i < globPattern.length) && (globPattern[i] == '/')
                    ) {
                        i++
                        regex.append("(?:.*/)?")
                    } else {
                        regex.append(".*")
                    }
                } else {
                    regex.append("[^/]*")
                }
            }
            '?' -> regex.append("[^/]")
            '\\' -> {
                if (i == globPattern.length) {
                    throw PatternSyntaxException("No character to escape", globPattern, i - 1)
                }
                val next = globPattern[i++]
                if (isRegexMeta(next)) {
                    regex.append('\\')
                }
                regex.append(next)
            }
            else -> {
                if (isRegexMeta(c)) {
                    regex.append('\\')
                }
                regex.append(c)
            }
        }
    }
    return regex.toString()
}