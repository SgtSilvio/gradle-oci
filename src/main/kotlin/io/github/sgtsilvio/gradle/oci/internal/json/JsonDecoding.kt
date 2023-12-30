package io.github.sgtsilvio.gradle.oci.internal.json

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.util.*

internal fun jsonValue(string: String) = JsonValue(run {
    val jsonTokener = JSONTokener(string)
    val value = try {
        jsonTokener.nextValue()
    } catch (e: JSONException) {
        throw JsonException.create("<syntax>", e)
    }
    val nextChar = jsonTokener.nextClean()
    if (nextChar.code != 0) {
        throw JsonException.create("<syntax>", "unexpected character after value: '$nextChar'")
    }
    value
})

internal fun jsonObject(string: String): JsonObject = jsonValue(string).asObject()

@DslMarker
internal annotation class JsonDecodingDsl

@JsonDecodingDsl
@JvmInline
internal value class JsonValue @PublishedApi internal constructor(private val delegate: Any) {

    fun isObject() = delegate is JSONObject

    fun asObject() = when (delegate) {
        is JSONObject -> JsonObject(delegate)
        else -> throw JsonException.create("", "must be an object, but is '$delegate'")
    }

    fun isArray() = delegate is JSONArray

    fun asArray() = when (delegate) {
        is JSONArray -> JsonArray(delegate)
        else -> throw JsonException.create("", "must be an array, but is '$delegate'")
    }

    fun isString() = delegate is String

    fun asString() = when (delegate) {
        is String -> delegate
        else -> throw JsonException.create("", "must be a string, but is '$delegate'")
    }

    fun isLong() = (delegate is Long) || (delegate is Int)

    fun asLong() = when (delegate) {
        is Long -> delegate
        is Int -> delegate.toLong()
        else -> throw JsonException.create("", "must be a long, but is '$delegate'")
    }

    fun isBoolean() = delegate is Boolean

    fun asBoolean() = when (delegate) {
        is Boolean -> delegate
        else -> throw JsonException.create("", "must be a boolean, but is '$delegate'")
    }
}

@JsonDecodingDsl
@JvmInline
internal value class JsonObject internal constructor(@PublishedApi internal val delegate: JSONObject) {

    fun hasKey(key: String) = delegate.has(key)

    inline fun <T> get(key: String, transform: JsonValue.() -> T): T {
        val value = delegate.getOrNull(key) ?: throw JsonException.create(key, "is required, but is missing")
        try {
            return JsonValue(value).transform()
        } catch (e: Throwable) {
            throw JsonException.create(key, e)
        }
    }

    inline fun <T> getOrNull(key: String, transform: JsonValue.() -> T): T? {
        val value = delegate.getOrNull(key) ?: return null
        try {
            return JsonValue(value).transform()
        } catch (e: Throwable) {
            throw JsonException.create(key, e)
        }
    }

    inline fun <T, M : MutableMap<in String, in T>> toMap(destination: M, transform: JsonValue.() -> T): M {
        for (key in delegate.keys()) {
            destination[key] = get(key, transform)
        }
        return destination
    }
}

internal fun JsonObject.getString(key: String) = get(key) { asString() }
internal fun JsonObject.getStringOrNull(key: String) = getOrNull(key) { asString() }

internal fun JsonObject.getLong(key: String) = get(key) { asLong() }

internal fun JsonObject.getBooleanOrNull(key: String) = getOrNull(key) { asBoolean() }

internal fun JsonObject.getStringList(key: String) = get(key) { asArray().toStringList() }
internal fun JsonObject.getStringListOrNull(key: String) = getOrNull(key) { asArray().toStringList() }

internal fun JsonObject.getStringSetOrNull(key: String) = getOrNull(key) { asArray().toStringSet() }

internal fun JsonObject.toStringMap() = toMap(TreeMap()) { asString() }
internal fun JsonObject.getStringMapOrNull(key: String) = getOrNull(key) { asObject().toStringMap() }

internal fun JsonObject.getInstantOrNull(key: String) = getOrNull(key) { Instant.parse(asString()) }

@JsonDecodingDsl
@JvmInline
internal value class JsonArray internal constructor(@PublishedApi internal val delegate: JSONArray) {

    inline fun <T> toList(transform: JsonValue.() -> T): List<T> {
        var i = 0
        try {
            return delegate.map { JsonValue(it).transform().also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }

    inline fun <T, S : MutableSet<in T>> toSet(destination: S, transform: JsonValue.() -> T): S {
        var i = 0
        try {
            return delegate.mapTo(destination) { JsonValue(it).transform().also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }

    inline fun <K, V, M : MutableMap<in K, in V>> toMap(destination: M, transform: JsonValue.() -> Pair<K, V>): M {
        var i = 0
        try {
            return delegate.associateTo(destination) { JsonValue(it).transform().also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }
}

internal fun JsonArray.toStringList() = toList { asString() }

internal fun JsonArray.toStringSet() = toSet(TreeSet()) { asString() }

internal class JsonException private constructor(
    private val path: String,
    messageWithoutPath: String,
    cause: Throwable?,
) : RuntimeException(messageWithoutPath, cause, false, false) {

    companion object {
        fun create(path: String, messageWithoutPath: String) = JsonException(path, messageWithoutPath, null)

        fun create(path: String, cause: Throwable) = when (cause) {
            is JsonException -> JsonException(combineJsonPaths(path, cause.path), cause.messageWithoutPath, cause.cause)
            else -> JsonException(path, "not valid: " + cause.message, cause)
        }

        fun create(arrayIndex: Int, cause: Throwable) = create("[$arrayIndex]", cause)

        private fun combineJsonPaths(parentPath: String, childPath: String) = when {
            childPath.isEmpty() -> parentPath
            childPath.startsWith("[") -> "$parentPath$childPath"
            else -> "$parentPath.$childPath"
        }
    }

    private val messageWithoutPath get() = super.message.toString()

    override val message get() = "'$path' $messageWithoutPath"
}

private fun JSONObject.getOrNull(key: String) = opt(key)?.takeIf { it != JSONObject.NULL }
