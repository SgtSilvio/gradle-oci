package io.github.sgtsilvio.gradle.oci.internal

import io.github.sgtsilvio.gradle.oci.OciDescriptor
import io.github.sgtsilvio.gradle.oci.OciManifestDescriptor

fun jsonStringBuilder(): JsonStringBuilder = JsonStringBuilderImpl()

interface JsonStringBuilder {
    fun addObject(block: (JsonObjectStringBuilder) -> Unit)

    fun addArray(block: (JsonValueStringBuilder) -> Unit)
}

interface JsonObjectStringBuilder {
    fun addKey(key: String): JsonValueStringBuilder
}

interface JsonValueStringBuilder : JsonStringBuilder {
    fun addValue(value: String)

    fun addValue(value: Long)

    fun addValue(value: Boolean)
}

private class JsonStringBuilderImpl : JsonStringBuilder, JsonObjectStringBuilder, JsonValueStringBuilder {
    private val stringBuilder = StringBuilder()
    private var needsComma = false

    override fun addObject(block: JsonObjectStringBuilder.() -> Unit) {
        addCommaIfNecessary()
        stringBuilder.append('{')
        block.invoke(this)
        stringBuilder.append('}')
        needsComma = true
    }

    override fun addArray(block: JsonValueStringBuilder.() -> Unit) {
        addCommaIfNecessary()
        stringBuilder.append('[')
        block.invoke(this)
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

    override fun toString(): String = stringBuilder.toString()

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
    if ((map != null) && map.isNotEmpty()) {
        addKey(key).addObject(map)
    }
}

fun JsonObjectStringBuilder.addOptionalKeyAndObject(key: String, set: Set<String>?) {
    if ((set != null) && set.isNotEmpty()) {
        addKey(key).addObject(set)
    }
}

fun JsonObjectStringBuilder.addOptionalKeyAndArray(key: String, list: List<String>?) {
    if ((list != null) && list.isNotEmpty()) {
        addKey(key).addArray(list)
    }
}

fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, ociDescriptor: OciDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addOptionalKeyAndObject("annotations", ociDescriptor.annotations.orNull)
        descriptorObject.addOptionalKeyAndValue("data", ociDescriptor.data.orNull)
        descriptorObject.addKey("digest").addValue(ociDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addValue(mediaType)
        descriptorObject.addKey("size").addValue(ociDescriptor.size.get())
        descriptorObject.addOptionalKeyAndArray("urls", ociDescriptor.urls.orNull)
    }
}

fun JsonValueStringBuilder.addOciManifestDescriptor(ociManifestDescriptor: OciManifestDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addOptionalKeyAndObject("annotations", ociManifestDescriptor.annotations.orNull)
        descriptorObject.addOptionalKeyAndValue("data", ociManifestDescriptor.data.orNull)
        descriptorObject.addKey("digest").addValue(ociManifestDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addValue(MANIFEST_MEDIA_TYPE)
        descriptorObject.addKey("platform").addObject { platformObject ->
            // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
            platformObject.addKey("architecture").addValue(ociManifestDescriptor.platform.architecture.get())
            platformObject.addKey("os").addValue(ociManifestDescriptor.platform.os.get())
            platformObject.addOptionalKeyAndArray("osFeatures", ociManifestDescriptor.platform.osFeatures.orNull)
            platformObject.addOptionalKeyAndValue("osVersion", ociManifestDescriptor.platform.osVersion.orNull)
            platformObject.addOptionalKeyAndValue("variant", ociManifestDescriptor.platform.variant.orNull)
        }
        descriptorObject.addKey("size").addValue(ociManifestDescriptor.size.get())
        descriptorObject.addOptionalKeyAndArray("urls", ociManifestDescriptor.urls.orNull)
    }
}