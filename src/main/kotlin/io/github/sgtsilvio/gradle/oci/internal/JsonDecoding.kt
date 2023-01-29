package io.github.sgtsilvio.gradle.oci.internal

import org.json.JSONArray
import org.json.JSONObject

@JvmInline
value class JsonValue(private val value: Any) {
    fun objectValue() = when (value) {
        is JSONObject -> value
        else -> throw JsonException("", "must be an object, but is '$value'")
    }

    fun arrayValue() = when (value) {
        is JSONArray -> value
        else -> throw JsonException("", "must be an array, but is '$value'")
    }

    fun stringValue() = when (value) {
        is String -> value
        else -> throw JsonException("", "must be a string, but is '$value'")
    }

    fun longValue() = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> throw JsonException("", "must be a long, but is '$value'")
    }
}

inline fun <T> JSONObject.key(key: String, transformer: JsonValue.() -> T): T {
    val value = opt(key) ?: throw JsonException(key, "is required, but is missing")
    try {
        return transformer.invoke(JsonValue(value))
    } catch (e: JsonException) {
        throw JsonException(key, e)
    }
}

inline fun <T> JSONObject.optionalKey(key: String, transformer: JsonValue.() -> T): T? {
    val value = opt(key) ?: return null
    try {
        return transformer.invoke(JsonValue(value))
    } catch (e: JsonException) {
        throw JsonException(key, e)
    }
}

inline fun <T, M : MutableMap<in String, in T>> JSONObject.toMap(destination: M, transformer: JsonValue.() -> T): M =
    toMap().mapValuesTo(destination) { transformer.invoke(JsonValue(it.value)) }

inline fun <T> JSONArray.toList(transformer: JsonValue.() -> T): List<T> {
    var i = 0
    try {
        return map { transformer.invoke(JsonValue(it)).also { i++ } }
    } catch (e: JsonException) {
        throw JsonException(i, e)
    }
}

inline fun <T, S : MutableSet<in T>> JSONArray.toSet(destination: S, transformer: JsonValue.() -> T): S {
    var i = 0
    try {
        return mapTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
    } catch (e: JsonException) {
        throw JsonException(i, e)
    }
}

inline fun <K, V, M : MutableMap<in K, in V>> JSONArray.toMap(
    destination: M,
    transformer: JsonValue.() -> Pair<K, V>,
): M {
    var i = 0
    try {
        return associateTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
    } catch (e: JsonException) {
        throw JsonException(i, e)
    }
}

class JsonException constructor(private val path: String, private val messageWithoutPath: String) :
    RuntimeException(messageWithoutPath, null, false, false) {

    override val message get() = "'$path' " + super.message

    constructor(parentPath: String, e: JsonException) : this(combineJsonPaths(parentPath, e.path), e.messageWithoutPath)

    constructor(arrayIndex: Int, e: JsonException) : this("[$arrayIndex]", e)
}

private fun combineJsonPaths(parentPath: String, path: String): String =
    if (path.isEmpty()) parentPath else if (path.startsWith("[")) "$parentPath$path" else "$parentPath.$path"