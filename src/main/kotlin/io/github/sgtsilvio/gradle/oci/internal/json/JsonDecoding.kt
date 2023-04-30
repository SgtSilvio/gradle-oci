package io.github.sgtsilvio.gradle.oci.internal.json

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*

fun jsonObject(string: String): JsonObject = JsonObject(
    try {
        JSONObject(string)
    } catch (e: JSONException) {
        throw JsonException.create("<syntax>", e)
    }
)

@DslMarker
annotation class JsonDecodingDsl

@JsonDecodingDsl
@JvmInline
value class JsonValue @PublishedApi internal constructor(private val delegate: Any) {

    fun asObject() = when (delegate) {
        is JSONObject -> JsonObject(delegate)
        is Map<*, *> -> JsonObject(JSONObject(delegate))
        else -> throw JsonException.create("", "must be an object, but is '$delegate'")
    }

    fun asArray() = when (delegate) {
        is JSONArray -> JsonArray(delegate)
        else -> throw JsonException.create("", "must be an array, but is '$delegate'")
    }

    fun asString() = when (delegate) {
        is String -> delegate
        else -> throw JsonException.create("", "must be a string, but is '$delegate'")
    }

    fun asLong() = when (delegate) {
        is Long -> delegate
        is Int -> delegate.toLong()
        else -> throw JsonException.create("", "must be a long, but is '$delegate'")
    }

    fun asBoolean() = when(delegate) {
        is Boolean -> delegate
        else -> throw JsonException.create("", "must be a boolean, but is '$delegate'")
    }
}

@JsonDecodingDsl
@JvmInline
value class JsonObject internal constructor(@PublishedApi internal val delegate: JSONObject) {

    fun hasKey(key: String) = delegate.has(key)

    inline fun <T> get(key: String, transformer: JsonValue.() -> T): T {
        val value = delegate.getOrNull(key) ?: throw JsonException.create(key, "is required, but is missing")
        try {
            return transformer.invoke(JsonValue(value))
        } catch (e: Throwable) {
            throw JsonException.create(key, e)
        }
    }

    inline fun <T> getOrNull(key: String, transformer: JsonValue.() -> T): T? {
        val value = delegate.getOrNull(key) ?: return null
        try {
            return transformer.invoke(JsonValue(value))
        } catch (e: Throwable) {
            throw JsonException.create(key, e)
        }
    }

    inline fun <T, M : MutableMap<in String, in T>> toMap(destination: M, transformer: JsonValue.() -> T): M =
        delegate.toMap().mapValuesTo(destination) { transformer.invoke(JsonValue(it.value)) }
}

fun JsonObject.getString(key: String) = get(key) { asString() }
fun JsonObject.getStringOrNull(key: String) = getOrNull(key) { asString() }

fun JsonObject.getLong(key: String) = get(key) { asLong() }

fun JsonObject.getBooleanOrNull(key: String) = getOrNull(key) { asBoolean() }

fun JsonObject.getStringList(key: String) = get(key) { asArray().toStringList() }
fun JsonObject.getStringListOrNull(key: String) = getOrNull(key) { asArray().toStringList() }

fun JsonObject.getStringSetOrNull(key: String) = getOrNull(key) { asArray().toStringSet() }

fun JsonObject.toStringMap() = toMap(TreeMap()) { asString() }
fun JsonObject.getStringMapOrNull(key: String) = getOrNull(key) { asObject().toStringMap() }

@JsonDecodingDsl
@JvmInline
value class JsonArray internal constructor(@PublishedApi internal val delegate: JSONArray) {

    inline fun <T> toList(transformer: JsonValue.() -> T): List<T> {
        var i = 0
        try {
            return delegate.map { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }

    inline fun <T, S : MutableSet<in T>> toSet(destination: S, transformer: JsonValue.() -> T): S {
        var i = 0
        try {
            return delegate.mapTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }

    inline fun <K, V, M : MutableMap<in K, in V>> toMap(destination: M, transformer: JsonValue.() -> Pair<K, V>): M {
        var i = 0
        try {
            return delegate.associateTo(destination) { transformer.invoke(JsonValue(it)).also { i++ } }
        } catch (e: Throwable) {
            throw JsonException.create(i, e)
        }
    }
}

fun JsonArray.toStringList() = toList { asString() }

fun JsonArray.toStringSet() = toSet(TreeSet()) { asString() }

class JsonException private constructor(
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

fun JSONObject.getOrNull(key: String) = opt(key)?.takeIf { it != JSONObject.NULL }
