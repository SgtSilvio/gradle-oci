package io.github.sgtsilvio.gradle.oci.internal.json

internal inline fun jsonObject(block: JsonObjectStringBuilder.() -> Unit): String {
    val builder = JsonStringBuilderImpl()
    builder.addObject(block)
    return builder.toString()
}

internal inline fun jsonArray(block: JsonArrayStringBuilder.() -> Unit): String {
    val builder = JsonStringBuilderImpl()
    builder.addArray(block)
    return builder.toString()
}

@DslMarker
internal annotation class JsonStringBuilderDsl

@JsonStringBuilderDsl
internal sealed interface JsonObjectStringBuilder {
    fun addString(key: String, value: String)

    fun addNumber(key: String, value: Long)

    fun addBoolean(key: String, value: Boolean)
}

internal inline fun JsonObjectStringBuilder.addObject(key: String, block: JsonObjectStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addObject(key, block)

internal inline fun JsonObjectStringBuilder.addArray(key: String, block: JsonArrayStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addArray(key, block)

@JsonStringBuilderDsl
internal sealed interface JsonArrayStringBuilder {
    fun addString(value: String)

    fun addNumber(value: Long)

    fun addBoolean(value: Boolean)
}

internal inline fun JsonArrayStringBuilder.addObject(block: JsonObjectStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addObject(block)

internal inline fun JsonArrayStringBuilder.addArray(block: JsonArrayStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addArray(block)

@PublishedApi
internal class JsonStringBuilderImpl : JsonObjectStringBuilder, JsonArrayStringBuilder {
    private val stringBuilder = StringBuilder()

    fun addKey(key: String) {
        addCommaIfNecessary()
        stringBuilder.append('"').append(key.jsonEscape()).append("\":")
    }

    fun startObject() {
        addCommaIfNecessary()
        stringBuilder.append('{')
    }

    fun endObject() {
        stringBuilder.append('}')
    }

    fun startArray() {
        addCommaIfNecessary()
        stringBuilder.append('[')
    }

    fun endArray() {
        stringBuilder.append(']')
    }

    internal inline fun addObject(key: String, block: JsonObjectStringBuilder.() -> Unit) {
        addKey(key)
        addObject(block)
    }

    internal inline fun addArray(key: String, block: JsonArrayStringBuilder.() -> Unit) {
        addKey(key)
        addArray(block)
    }

    override fun addString(key: String, value: String) {
        addKey(key)
        addString(value)
    }

    override fun addNumber(key: String, value: Long) {
        addKey(key)
        addNumber(value)
    }

    override fun addBoolean(key: String, value: Boolean) {
        addKey(key)
        addBoolean(value)
    }

    internal inline fun addObject(block: JsonObjectStringBuilder.() -> Unit) {
        startObject()
        block()
        endObject()
    }

    internal inline fun addArray(block: JsonArrayStringBuilder.() -> Unit) {
        startArray()
        block()
        endArray()
    }

    override fun addString(value: String) {
        addCommaIfNecessary()
        stringBuilder.append('"').append(value.jsonEscape()).append('"')
    }

    override fun addNumber(value: Long) {
        addCommaIfNecessary()
        stringBuilder.append(value.toString())
    }

    override fun addBoolean(value: Boolean) {
        addCommaIfNecessary()
        stringBuilder.append(value.toString())
    }

    override fun toString() = stringBuilder.toString()

    private fun addCommaIfNecessary() {
        if (stringBuilder.isNotEmpty() && (stringBuilder.last() !in "{[:")) {
            stringBuilder.append(',')
        }
    }
}

private val jsonEscapeRegex = Regex("[\u0000-\u0019\"\\\\]")

internal fun String.jsonEscape() = replace(jsonEscapeRegex) {
    when (val c = it.value[0]) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\t' -> "\\t"
        '\n' -> "\\n"
        '\u000C' -> "\\f"
        '\r' -> "\\r"
        else -> "\\u%04X".format(c.code)
    }
}

// TODO no sorting, use supplied sorting => ensure we always pass a sorted map
internal fun JsonObjectStringBuilder.addAll(map: Map<String, String>) = map.toSortedMap().forEach { addString(it.key, it.value) }
internal fun JsonObjectStringBuilder.addAll(set: Set<String>) = set.toSortedSet().forEach { addObject(it) {} }
internal fun JsonArrayStringBuilder.addAll(list: Iterable<String>) = list.forEach { addString(it) }

internal fun JsonObjectStringBuilder.addObject(key: String, map: Map<String, String>) = addObject(key) { addAll(map) }
internal fun JsonObjectStringBuilder.addObject(key: String, set: Set<String>) = addObject(key) { addAll(set) }
internal fun JsonObjectStringBuilder.addArray(key: String, list: Iterable<String>) = addArray(key) { addAll(list) }

internal fun JsonObjectStringBuilder.addStringIfNotNull(key: String, value: String?) {
    if (value != null) {
        addString(key, value)
    }
}

internal fun JsonObjectStringBuilder.addStringIfNotEmpty(key: String, value: String?) {
    if (!value.isNullOrEmpty()) {
        addString(key, value)
    }
}

internal fun JsonObjectStringBuilder.addObjectIfNotEmpty(key: String, map: Map<String, String>?) {
    if (!map.isNullOrEmpty()) {
        addObject(key, map)
    }
}

internal fun JsonObjectStringBuilder.addObjectIfNotEmpty(key: String, set: Set<String>?) {
    if (!set.isNullOrEmpty()) {
        addObject(key, set)
    }
}

internal fun JsonObjectStringBuilder.addArrayIfNotNull(key: String, list: Collection<String>?) {
    if (list != null) {
        addArray(key, list)
    }
}

internal fun JsonObjectStringBuilder.addArrayIfNotEmpty(key: String, list: Collection<String>?) {
    if (!list.isNullOrEmpty()) {
        addArray(key, list)
    }
}

internal inline fun <T> JsonObjectStringBuilder.addArray(
    key: String,
    list: Iterable<T>,
    block: JsonArrayStringBuilder.(T) -> Unit,
) = addArray(key) { list.forEach { block(it) } }

internal inline fun <T> JsonObjectStringBuilder.addArrayIfNotEmpty(
    key: String,
    list: Collection<T>?,
    block: JsonArrayStringBuilder.(T) -> Unit,
) {
    if (!list.isNullOrEmpty()) {
        addArray(key, list, block)
    }
}
