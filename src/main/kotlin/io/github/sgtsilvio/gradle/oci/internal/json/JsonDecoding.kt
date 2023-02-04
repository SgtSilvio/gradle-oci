package io.github.sgtsilvio.gradle.oci.internal.json

import org.json.JSONArray
import org.json.JSONObject

fun jsonObject(string: String): JsonObject = JsonObject(JSONObject(string))

@DslMarker
annotation class JsonDecodingDsl

@JsonDecodingDsl
@JvmInline
value class JsonValue @PublishedApi internal constructor(private val delegate: Any) {

    fun objectValue() = when (delegate) {
        is JSONObject -> JsonObject(delegate)
        else -> throw JsonException("", "must be an object, but is '$delegate'")
    }

    fun arrayValue() = when (delegate) {
        is JSONArray -> JsonArray(delegate)
        else -> throw JsonException("", "must be an array, but is '$delegate'")
    }

    fun stringValue() = when (delegate) {
        is String -> delegate
        else -> throw JsonException("", "must be a string, but is '$delegate'")
    }

    fun longValue() = when (delegate) {
        is Long -> delegate
        is Int -> delegate.toLong()
        else -> throw JsonException("", "must be a long, but is '$delegate'")
    }
}

@JsonDecodingDsl
@JvmInline
value class JsonObject internal constructor(@PublishedApi internal val delegate: JSONObject) {

    fun hasKey(key: String) = delegate.has(key)

    inline fun <T> key(key: String, transformer: JsonValue.() -> T): T {
        val value = delegate.opt(key) ?: throw JsonException(key, "is required, but is missing")
        try {
            return transformer.invoke(JsonValue(value))
        } catch (e: JsonException) {
            throw JsonException(key, e)
        }
    }

    inline fun <T> optionalKey(key: String, transformer: JsonValue.() -> T): T? {
        val value = delegate.opt(key) ?: return null
        try {
            return transformer.invoke(JsonValue(value))
        } catch (e: JsonException) {
            throw JsonException(key, e)
        }
    }

    inline fun <T, M : MutableMap<in String, in T>> toMap(destination: M, transformer: JsonValue.() -> T): M =
        delegate.toMap().mapValuesTo(destination) { transformer.invoke(JsonValue(it.value)) }
}

@JsonDecodingDsl
@JvmInline
value class JsonArray internal constructor(@PublishedApi internal val delegate: JSONArray) {

    inline fun <T> toList(transformer: JsonValue.() -> T): List<T> {
        var i = 0
        try {
            return delegate.map { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: JsonException) {
            throw JsonException(i, e)
        }
    }

    inline fun <T, S : MutableSet<in T>> toSet(destination: S, transformer: JsonValue.() -> T): S {
        var i = 0
        try {
            return delegate.mapTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: JsonException) {
            throw JsonException(i, e)
        }
    }

    inline fun <K, V, M : MutableMap<in K, in V>> toMap(destination: M, transformer: JsonValue.() -> Pair<K, V>): M {
        var i = 0
        try {
            return delegate.associateTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: JsonException) {
            throw JsonException(i, e)
        }
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