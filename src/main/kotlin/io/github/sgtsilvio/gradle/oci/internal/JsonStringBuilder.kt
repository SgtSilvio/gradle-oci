package io.github.sgtsilvio.gradle.oci.internal

fun jsonObject(block: (JsonObjectStringBuilder) -> Unit): String {
    val builder = JsonStringBuilderImpl()
    builder.addObject(block)
    return builder.toString()
}

fun jsonArray(block: (JsonValueStringBuilder) -> Unit): String {
    val builder = JsonStringBuilderImpl()
    builder.addArray(block)
    return builder.toString()
}

interface JsonObjectStringBuilder {
    fun addKey(key: String): JsonValueStringBuilder
}

interface JsonValueStringBuilder {
    fun addObject(block: (JsonObjectStringBuilder) -> Unit)

    fun addArray(block: (JsonValueStringBuilder) -> Unit)

    fun addValue(value: String)

    fun addValue(value: Long)

    fun addValue(value: Boolean)
}

private class JsonStringBuilderImpl : JsonObjectStringBuilder, JsonValueStringBuilder {
    private val stringBuilder = StringBuilder()
    private var needsComma = false

    override fun addObject(block: JsonObjectStringBuilder.() -> Unit) {
        addCommaIfNecessary()
        stringBuilder.append('{')
        block(this)
        stringBuilder.append('}')
        needsComma = true
    }

    override fun addArray(block: JsonValueStringBuilder.() -> Unit) {
        addCommaIfNecessary()
        stringBuilder.append('[')
        block(this)
        stringBuilder.append(']')
        needsComma = true
    }

    override fun addKey(key: String): JsonValueStringBuilder {
        addCommaIfNecessary()
        stringBuilder.append("\"${escape(key)}\":")
        return this
    }

    override fun addValue(value: String) {
        addCommaIfNecessary()
        stringBuilder.append("\"${escape(value)}\"")
        needsComma = true
    }

    override fun addValue(value: Long) {
        addCommaIfNecessary()
        stringBuilder.append("$value")
        needsComma = true
    }

    override fun addValue(value: Boolean) {
        addCommaIfNecessary()
        stringBuilder.append("$value")
        needsComma = true
    }

    override fun toString() = stringBuilder.toString()

    private fun addCommaIfNecessary() {
        if (needsComma) {
            needsComma = false
            stringBuilder.append(',')
        }
    }

    private fun escape(string: String) = string.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun JsonValueStringBuilder.addObject(map: Map<String, String>) {
    addObject { jsonObject -> map.toSortedMap().forEach { jsonObject.addKey(it.key).addValue(it.value) } }
}

fun JsonValueStringBuilder.addObject(set: Set<String>) {
    addObject { jsonObject -> set.toSortedSet().forEach { jsonObject.addKey(it).addObject {} } }
}

fun JsonValueStringBuilder.addArray(iterable: Iterable<String>) {
    addArray { jsonArray -> iterable.forEach { jsonArray.addValue(it) } }
}

fun JsonObjectStringBuilder.addOptionalKeyAndValue(key: String, value: String?) {
    if (value != null) {
        addKey(key).addValue(value)
    }
}

fun JsonObjectStringBuilder.addOptionalKeyAndObject(key: String, map: Map<String, String>?) {
    if (!map.isNullOrEmpty()) {
        addKey(key).addObject(map)
    }
}

fun JsonObjectStringBuilder.addOptionalKeyAndObject(key: String, set: Set<String>?) {
    if (!set.isNullOrEmpty()) {
        addKey(key).addObject(set)
    }
}

fun JsonObjectStringBuilder.addOptionalKeyAndArray(key: String, list: List<String>?) {
    if (!list.isNullOrEmpty()) {
        addKey(key).addArray(list)
    }
}