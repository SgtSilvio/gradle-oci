package io.github.sgtsilvio.gradle.oci.internal

import java.io.Writer

fun String.escapePropertiesKey() = replace(Regex("[:= \\t\\f]"), "\\\\$0")

fun String.escapePropertiesValue(): String {
    val s = replace("\\", "\\\\").replace("\n", "\\n")
    return if (startsWith(" ") || startsWith("\t") || startsWith("\u000C")) "\\$s" else s
}

fun Writer.writeProperty(key: String, value: String) {
    write(key.escapePropertiesKey())
    write('='.code)
    write(value.escapePropertiesValue())
    write('\n'.code)
}