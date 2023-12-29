package io.github.sgtsilvio.gradle.oci.internal

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

@JvmInline
value class CamelCaseString(val string: String) {
    override fun toString() = string
}

operator fun CamelCaseString.plus(other: CamelCaseString) = CamelCaseString(string.concatCamelCase(other.string))

fun CamelCaseString.toKebabCase(): KebabCaseString {
    val stringBuilder = StringBuilder(string.length + 2)
    for (c in string) {
        if (c.isUpperCase()) {
            stringBuilder.append('-').append(c.lowercaseChar())
        } else {
            stringBuilder.append(c)
        }
    }
    return KebabCaseString(stringBuilder.toString())
}

@JvmInline
value class KebabCaseString(val string: String) {
    override fun toString() = string
}

operator fun KebabCaseString.plus(other: KebabCaseString) = KebabCaseString(string.concatKebabCase(other.string))

fun KebabCaseString.toCamelCase(): CamelCaseString {
    val stringBuilder = StringBuilder(string.length)
    var prevIsHyphen = false
    for (c in string) {
        val isHyphen = c == '-'
        if (!isHyphen) {
            if (prevIsHyphen) {
                stringBuilder.append(c.uppercaseChar())
            } else {
                stringBuilder.append(c)
            }
        }
        prevIsHyphen = isHyphen
    }
    return CamelCaseString(stringBuilder.toString())
}

fun String.isCamelCase(): Boolean {
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

fun String.isKebabCase(): Boolean {
    when {
        isEmpty() -> return true
        this[0] == '-' -> return false
        this[lastIndex] == '-' -> return false
        else -> {
            var prevIsHyphen = false
            for (c in this) {
                if (c.isSeparatorOrWordStart()) {
                    return false
                }
                val isHyphen = c == '-'
                if (isHyphen && prevIsHyphen) {
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

fun String.toCamelCase(): CamelCaseString {
    if (isCamelCase()) {
        return CamelCaseString(this)
    }
    val stringBuilder = StringBuilder(length)
    convertCase { c, isIntermediateWordStart ->
        stringBuilder.append(if (isIntermediateWordStart) c.uppercaseChar() else c.lowercaseChar())
    }
    return CamelCaseString(stringBuilder.toString())
}

fun String.toKebabCase(): KebabCaseString {
    if (isKebabCase()) {
        return KebabCaseString(this)
    }
    val stringBuilder = StringBuilder(length + 2)
    convertCase { c, isIntermediateWordStart ->
        if (isIntermediateWordStart) {
            stringBuilder.append('-')
        }
        stringBuilder.append(c.lowercaseChar())
    }
    return KebabCaseString(stringBuilder.toString())
}

private fun Char.isSeparator() = this in " -_"

private fun Char.isWordStart() = isUpperCase() || isTitleCase()

private fun Char.isSeparatorOrWordStart() = isSeparator() || isWordStart()

fun main() {
    println("test-test".toKebabCase())
    println("test-test".toKebabCase().toCamelCase())
    println("test-test".toCamelCase())
    println("test-test".toCamelCase().toKebabCase())
    println("TEST TEST".toKebabCase())
    println("TEST TEST".toKebabCase().toCamelCase())
    println("TEST TEST".toCamelCase())
    println("TEST TEST".toCamelCase().toKebabCase())

    println("TEST TEST".toKebabCase())
    println("TEST TEST".toKebabCase().toCamelCase())
    println("TEST TEST".toCamelCase())
    println("TEST TEST".toCamelCase().toKebabCase())

    println("-test-test--test-TEST TEST--".toKebabCase())
    println("-test-test--test-TEST TEST--".toKebabCase().toCamelCase())
    println("-test-test--test-TEST TEST--".toCamelCase())
    println("-test-test--test-TEST TEST--".toCamelCase().toKebabCase())

    for (string in listOf("XMLTransformer", "IOBuffer", "IObuffer", "TestIO", "TestI", "ITest", "testIO", "testI", "iTest")) {
        println(string.isKebabCase())
        println(string.isCamelCase())
        println(string.toKebabCase())
        println(string.toKebabCase().toCamelCase())
        println(string.toCamelCase())
        println(string.toCamelCase().toKebabCase())
        println()
    }
}

@JvmInline
value class Phrase(val words: Array<String>)

operator fun Phrase.plus(other: Phrase) = when {
    other.words.isEmpty() -> this
    words.isEmpty() -> other
    else -> Phrase(words + other.words)
}

operator fun Phrase.plus(other: String) = this + other.toPhrase()

fun String.toPhrase(): Phrase {
    val words = mutableListOf<String>()
    val stringBuilder = StringBuilder()
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
            if (isIntermediateWordStart) {
                words += stringBuilder.toString()
                stringBuilder.clear()
            }
            stringBuilder.append(c.lowercaseChar())
        }
    }
    if (stringBuilder.isNotEmpty()) {
        words += stringBuilder.toString()
    }
    return Phrase(words.toTypedArray())
}

fun Phrase.toCamelCase() = when (words.size) {
    0 -> ""
    1 -> words[0]
    else -> {
        val stringBuilder = StringBuilder(words.sumOf { it.length })
        stringBuilder.append(words[0])
        var i = 1
        while (i < words.size) {
            val word = words[i]
            stringBuilder.append(word[0].uppercaseChar()).append(word, 1, word.length)
            i++
        }
        stringBuilder.toString()
    }
}

fun Phrase.toKebabCase() = when (words.size) {
    0 -> ""
    1 -> words[0]
    else -> {
        val stringBuilder = StringBuilder(words.sumOf { it.length } + words.size - 1)
        stringBuilder.append(words[0])
        var i = 1
        while (i < words.size) {
            stringBuilder.append('-').append(words[i])
            i++
        }
        stringBuilder.toString()
    }
}
