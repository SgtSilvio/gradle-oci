package io.github.sgtsilvio.gradle.oci.internal.json

inline fun jsonObject(block: JsonObjectStringBuilder.() -> Unit): String {
    val builder = JsonStringBuilderImpl()
    builder.addObject(block)
    return builder.toString()
}

@DslMarker
annotation class JsonStringBuilderDsl

@JsonStringBuilderDsl
sealed interface JsonObjectStringBuilder {
    fun addString(key: String, value: String)

    fun addNumber(key: String, value: Long)

    fun addBoolean(key: String, value: Boolean)
}

inline fun JsonObjectStringBuilder.addObject(key: String, block: JsonObjectStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addObject(key, block)

inline fun JsonObjectStringBuilder.addArray(key: String, block: JsonArrayStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addArray(key, block)

@JsonStringBuilderDsl
sealed interface JsonArrayStringBuilder {
    fun addString(value: String)

    fun addNumber(value: Long)

    fun addBoolean(value: Boolean)
}

inline fun JsonArrayStringBuilder.addObject(block: JsonObjectStringBuilder.() -> Unit) =
    (this as JsonStringBuilderImpl).addObject(block)

inline fun JsonArrayStringBuilder.addArray(block: JsonArrayStringBuilder.() -> Unit) =
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

    inline fun addObject(key: String, block: JsonObjectStringBuilder.() -> Unit) {
        addKey(key)
        addObject(block)
    }

    inline fun addArray(key: String, block: JsonArrayStringBuilder.() -> Unit) {
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

    inline fun addObject(block: JsonObjectStringBuilder.() -> Unit) {
        startObject()
        block()
        endObject()
    }

    inline fun addArray(block: JsonArrayStringBuilder.() -> Unit) {
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
fun JsonObjectStringBuilder.addAll(map: Map<String, String>) = map.toSortedMap().forEach { addString(it.key, it.value) }
fun JsonObjectStringBuilder.addAll(set: Set<String>) = set.toSortedSet().forEach { addObject(it) {} }
fun JsonArrayStringBuilder.addAll(list: Iterable<String>) = list.forEach { addString(it) }

fun JsonObjectStringBuilder.addObject(key: String, map: Map<String, String>) = addObject(key) { addAll(map) }
fun JsonObjectStringBuilder.addObject(key: String, set: Set<String>) = addObject(key) { addAll(set) }
fun JsonObjectStringBuilder.addArray(key: String, list: Iterable<String>) = addArray(key) { addAll(list) }

fun JsonObjectStringBuilder.addStringIfNotNull(key: String, value: String?) {
    if (value != null) {
        addString(key, value)
    }
}

fun JsonObjectStringBuilder.addStringIfNotEmpty(key: String, value: String?) {
    if (!value.isNullOrEmpty()) {
        addString(key, value)
    }
}

fun JsonObjectStringBuilder.addObjectIfNotEmpty(key: String, map: Map<String, String>?) {
    if (!map.isNullOrEmpty()) {
        addObject(key, map)
    }
}

fun JsonObjectStringBuilder.addObjectIfNotEmpty(key: String, set: Set<String>?) {
    if (!set.isNullOrEmpty()) {
        addObject(key, set)
    }
}

fun JsonObjectStringBuilder.addArrayIfNotNull(key: String, list: Collection<String>?) {
    if (list != null) {
        addArray(key, list)
    }
}

fun JsonObjectStringBuilder.addArrayIfNotEmpty(key: String, list: Collection<String>?) {
    if (!list.isNullOrEmpty()) {
        addArray(key, list)
    }
}

inline fun <T> JsonObjectStringBuilder.addArray(
    key: String,
    list: Iterable<T>,
    block: JsonArrayStringBuilder.(T) -> Unit,
) = addArray(key) { list.forEach { block(it) } }

inline fun <T> JsonObjectStringBuilder.addArrayIfNotEmpty(
    key: String,
    list: Collection<T>?,
    block: JsonArrayStringBuilder.(T) -> Unit,
) {
    if (!list.isNullOrEmpty()) {
        addArray(key, list, block)
    }
}
