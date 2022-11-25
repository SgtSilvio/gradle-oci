package io.github.sgtsilvio.gradle.oci.internal

import org.json.JSONArray
import org.json.JSONObject

fun Any.stringValue(): String {
    if (this !is String) {
        throw JsonException("", "must be a string, but is '$this'")
    }
    return this
}

fun Any.objectValue(): JSONObject {
    if (this !is JSONObject) {
        throw JsonException("", "must be an object, but is '$this'")
    }
    return this
}

fun Any.arrayValue(): JSONArray {
    if (this !is JSONArray) {
        throw JsonException("", "must be an array, but is '$this'")
    }
    return this
}

inline fun <T> JSONObject.key(key: String, transformer: Any.() -> T): T {
    val value = opt(key) ?: throw JsonException(key, "is required, but is missing")
    try {
        return transformer.invoke(value)
    } catch (e: JsonException) {
        throw JsonException(key, e)
    }
}

inline fun <T> JSONObject.optionalKey(key: String, transformer: Any.() -> T): T? {
    val value = opt(key) ?: return null
    try {
        return transformer.invoke(value)
    } catch (e: JsonException) {
        throw JsonException(key, e)
    }
}

inline fun <T> JSONObject.toMap(transformer: Any.() -> T): Map<String, T> =
    toMap().mapValues { transformer.invoke(it.value) }

inline fun <T> JSONArray.toList(transformer: Any.() -> T): List<T> {
    var i = 0
    try {
        return map { transformer.invoke(it).also { i++ } }
    } catch (e: JsonException) {
        throw JsonException(i, e)
    }
}

inline fun <T> JSONArray.toSet(transformer: Any.() -> T): Set<T> {
    var i = 0
    try {
        return mapTo(mutableSetOf()) { transformer.invoke(it).also { i++ } }
    } catch (e: JsonException) {
        throw JsonException(i, e)
    }
}

inline fun <K, V> JSONArray.toMap(transformer: Any.() -> Pair<K, V>): Map<K, V> {
    var i = 0
    try {
        return associate { transformer.invoke(it).also { i++ } }
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