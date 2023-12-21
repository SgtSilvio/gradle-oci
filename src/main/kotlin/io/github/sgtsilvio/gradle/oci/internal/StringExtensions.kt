package io.github.sgtsilvio.gradle.oci.internal

internal fun String.fromCamelToKebabCase(): String {
    val stringBuilder = StringBuilder(length)
    for (c in this) {
        if (c.isUpperCase()) {
            stringBuilder.append('-')
            stringBuilder.append(c.lowercaseChar())
        } else {
            stringBuilder.append(c)
        }
    }
    return stringBuilder.toString()
}
