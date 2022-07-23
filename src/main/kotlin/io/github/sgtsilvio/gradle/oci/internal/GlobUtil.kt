package io.github.sgtsilvio.gradle.oci.internal

import java.util.regex.PatternSyntaxException

private const val regexMetaChars = "\\^$.|?*+()[{"

private fun isRegexMeta(c: Char) = regexMetaChars.contains(c)

fun convertToRegex(globPattern: String): String {
//    val regex = StringBuilder("^")
    val regex = StringBuilder()
    var i = 0
    while (i < globPattern.length) {
        when (val c = globPattern[i++]) {
            '*' -> {
                if ((i < globPattern.length) && (globPattern[i] == '*')) {
                    i++
                    if (// last char is either '/' or string start
                        ((i < 3) || (globPattern[i - 3] == '/')) &&
                        // next char is '/' and additional chars
                        ((i + 1) < globPattern.length) && (globPattern[i] == '/')
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
//    return regex.append('$').toString()
    return regex.toString()
}