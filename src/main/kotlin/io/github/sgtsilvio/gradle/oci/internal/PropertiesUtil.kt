package io.github.sgtsilvio.gradle.oci.internal

fun String.escapePropertiesKey() = replace(Regex("[:= \\t\\f]"), "\\\\$0")

fun String.escapePropertiesValue(): String {
    val s = replace("\\", "\\\\").replace("\n", "\\n")
    return if (startsWith(" ") || startsWith("\t") || startsWith("\u000C")) "\$s" else s
}