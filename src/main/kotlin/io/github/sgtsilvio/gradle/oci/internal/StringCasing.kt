package io.github.sgtsilvio.gradle.oci.internal

internal fun String.isCamelCase(): Boolean {
    when {
        isEmpty() -> return true
        this[0].isWordStart() -> return false
        else -> {
            var wordStartCount = 0
            for (c in this) {
                if (c.isSeparator()) {
                    return false
                }
                if (c.isWordStart()) {
                    wordStartCount++
                    if (wordStartCount > 2) {
                        return false
                    }
                } else {
                    wordStartCount = 0
                }
            }
            return wordStartCount < 2
        }
    }
}

internal fun String.isKebabCase(): Boolean {
    when {
        isEmpty() -> return true
        this[0] == '-' -> return false
        this[lastIndex] == '-' -> return false
        else -> {
            var prevIsHyphen = false
            for (c in this) {
                val isHyphen = c == '-'
                if (isHyphen) {
                    if (prevIsHyphen) {
                        return false
                    }
                } else if (c.isSeparatorOrWordStart()) {
                    return false
                }
                prevIsHyphen = isHyphen
            }
            return true
        }
    }
}

private const val STATE_EMPTY = 0
private const val STATE_SEPARATOR = 1
private const val STATE_LOWERCASE = 2
private const val STATE_UPPERCASE = 3

private inline fun String.convertCase(consume: (Char, isIntermediateWordStart: Boolean) -> Unit) {
    var state = STATE_EMPTY
    for ((i, c) in withIndex()) {
        if (c.isSeparator()) {
            if (state != STATE_EMPTY) {
                state = STATE_SEPARATOR
            }
        } else {
            var isIntermediateWordStart: Boolean
            if (c.isWordStart()) {
                isIntermediateWordStart = (state != STATE_EMPTY) && !((state == STATE_UPPERCASE) && (((i + 1) == length) || this[i + 1].isSeparatorOrWordStart()))
                state = STATE_UPPERCASE
            } else {
                isIntermediateWordStart = state == STATE_SEPARATOR
                state = STATE_LOWERCASE
            }
            consume(c, isIntermediateWordStart)
        }
    }
}

internal fun String.camelCase(): String {
    if (isCamelCase()) {
        return this
    }
    val stringBuilder = StringBuilder(length)
    convertCase { c, isIntermediateWordStart ->
        stringBuilder.append(if (isIntermediateWordStart) c.uppercaseChar() else c.lowercaseChar())
    }
    return stringBuilder.toString()
}

internal fun String.kebabCase(): String {
    if (isKebabCase()) {
        return this
    }
    val stringBuilder = StringBuilder(length + 2)
    convertCase { c, isIntermediateWordStart ->
        if (isIntermediateWordStart) {
            stringBuilder.append('-')
        }
        stringBuilder.append(c.lowercaseChar())
    }
    return stringBuilder.toString()
}

private fun Char.isSeparator() = this in " -_"

private fun Char.isWordStart() = isUpperCase() || isTitleCase()

private fun Char.isSeparatorOrWordStart() = isSeparator() || isWordStart()

internal fun String.concatCamelCase(other: String) = when {
    other.isEmpty() -> this
    isEmpty() -> other
    else -> this + other[0].uppercaseChar() + other.substring(1)
}

internal fun String.concatKebabCase(other: String) = when {
    other.isEmpty() -> this
    isEmpty() -> other
    else -> "$this-$other"
}
