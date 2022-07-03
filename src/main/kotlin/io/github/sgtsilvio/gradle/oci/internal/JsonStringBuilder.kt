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
        stringBuilder.append("\"$key\":")
        return this
    }

    override fun addValue(value: String) {
        stringBuilder.append("\"$value\"")
        needsComma = true
    }

    override fun addValue(value: Long) {
        stringBuilder.append("$value")
        needsComma = true
    }

    override fun addValue(value: Boolean) {
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
}

fun JsonObjectStringBuilder.addKeyAndValue(key: String, value: String?) {
    if (value != null) {
        addKey(key).addValue(value)
    }
}

fun JsonObjectStringBuilder.addKeyAndObject(key: String, map: Map<String, String>?) {
    if ((map != null) && map.isNotEmpty()) {
        addKey(key).addObject { jsonObject ->
            map.toSortedMap().forEach { (key, value) -> jsonObject.addKey(key).addValue(value) }
        }
    }
}

fun JsonObjectStringBuilder.addKeyAndArray(key: String, list: List<String>?) {
    if ((list != null) && list.isNotEmpty()) {
        addKey(key).addArray { jsonArray -> list.forEach { jsonArray.addValue(it) } }
    }
}

fun JsonValueStringBuilder.addOciDescriptor(mediaType: String, ociDescriptor: OciDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addKeyAndObject("annotations", ociDescriptor.annotations.orNull)
        descriptorObject.addKeyAndValue("data", ociDescriptor.data.orNull)
        descriptorObject.addKey("digest").addValue(ociDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addValue(mediaType)
        descriptorObject.addKey("size").addValue(ociDescriptor.size.get())
        descriptorObject.addKeyAndArray("urls", ociDescriptor.urls.orNull)
    }
}

fun JsonValueStringBuilder.addOciManifestDescriptor(ociManifestDescriptor: OciManifestDescriptor) {
    addObject { descriptorObject ->
        // sorted for canonical json: annotations, data, digest, mediaType, size, urls
        descriptorObject.addKeyAndObject("annotations", ociManifestDescriptor.annotations.orNull)
        descriptorObject.addKeyAndValue("data", ociManifestDescriptor.data.orNull)
        descriptorObject.addKey("digest").addValue(ociManifestDescriptor.digest.get())
        descriptorObject.addKey("mediaType").addValue("application/vnd.oci.image.manifest.v1+json")
        descriptorObject.addKey("platform").addObject { platformObject ->
            // sorted for canonical json: architecture, os, osFeatures, osVersion, variant
            platformObject.addKey("architecture").addValue(ociManifestDescriptor.platform.architecture.get())
            platformObject.addKey("os").addValue(ociManifestDescriptor.platform.os.get())
            platformObject.addKeyAndArray("osFeatures", ociManifestDescriptor.platform.osFeatures.orNull)
            platformObject.addKeyAndValue("osVersion", ociManifestDescriptor.platform.osVersion.orNull)
            platformObject.addKeyAndValue("variant", ociManifestDescriptor.platform.variant.orNull)
        }
        descriptorObject.addKey("size").addValue(ociManifestDescriptor.size.get())
        descriptorObject.addKeyAndArray("urls", ociManifestDescriptor.urls.orNull)
    }
}