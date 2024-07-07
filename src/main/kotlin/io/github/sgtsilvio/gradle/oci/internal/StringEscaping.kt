package io.github.sgtsilvio.gradle.oci.internal

internal fun String.escapeReplace(toReplace: Char, escapeChar: Char) =
    replace(escapeChar.toString(), "${escapeChar}0").replace(toReplace.toString(), "${escapeChar}1")

internal fun String.unescapeReplace(toReplace: Char, escapeChar: Char) =
    replace("${escapeChar}1", toReplace.toString()).replace("${escapeChar}0", escapeChar.toString())
