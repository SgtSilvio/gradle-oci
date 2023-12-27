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
value class CamelCaseString(val string: String)

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
value class KebabCaseString(val string: String)

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
        this[0].isUpperCase() -> return false
        else -> {
            var upperCaseCount = 0
            for (c in this) {
                if (c.isSeparator()) {
                    return false
                }
                if (c.isUpperCase()) {
                    upperCaseCount++
                    if (upperCaseCount > 2) {
                        return false
                    }
                } else {
                    upperCaseCount = 0
                }
            }
            return upperCaseCount < 2
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
                if (c.isSeparatorOrUpperCase()) {
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

fun String.toCamelCase(): CamelCaseString {
    if (isCamelCase()) {
        return CamelCaseString(this)
    }
    val stringBuilder = StringBuilder(length)
    var state = STATE_EMPTY
    for ((i, c) in withIndex()) {
        if (c.isSeparator()) {
            if (state != STATE_EMPTY) {
                state = STATE_SEPARATOR
            }
        } else {
            var transformedC = c
            if (c.isUpperCase()) {
                if ((state == STATE_EMPTY) || ((state == STATE_UPPERCASE) && (((i + 1) == length) || this[i + 1].isSeparatorOrUpperCase()))) {
                    transformedC = c.lowercaseChar()
                }
                state = STATE_UPPERCASE
            } else {
                if (state == STATE_SEPARATOR) {
                    transformedC = c.uppercaseChar()
                }
                state = STATE_LOWERCASE
            }
            stringBuilder.append(transformedC)
        }
    }
    return CamelCaseString(stringBuilder.toString())
}

fun String.toKebabCase(): KebabCaseString {
    if (isKebabCase()) {
        return KebabCaseString(this)
    }
    val stringBuilder = StringBuilder(length + 2)
    var state = STATE_EMPTY
    for ((i, c) in withIndex()) {
        if (c.isSeparator()) {
            if (state != STATE_EMPTY) {
                state = STATE_SEPARATOR
            }
        } else if (c.isUpperCase()) {
            if (!((state == STATE_EMPTY) || ((state == STATE_UPPERCASE) && (((i + 1) == length) || this[i + 1].isSeparatorOrUpperCase())))) {
                stringBuilder.append('-')
            }
            stringBuilder.append(c.lowercaseChar())
            state = STATE_UPPERCASE
        } else {
            if (state == STATE_SEPARATOR) {
                stringBuilder.append('-')
            }
            stringBuilder.append(c)
            state = STATE_LOWERCASE
        }
    }
    return KebabCaseString(stringBuilder.toString())
}

private fun Char.isSeparator() = this in " -_"

private fun Char.isSeparatorOrUpperCase() = isSeparator() || isUpperCase()

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
