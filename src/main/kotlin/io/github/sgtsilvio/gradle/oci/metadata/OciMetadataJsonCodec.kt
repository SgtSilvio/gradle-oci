package io.github.sgtsilvio.gradle.oci.metadata

import io.github.sgtsilvio.gradle.oci.internal.json.*

internal fun OciMetadata.encodeToJsonString() = jsonObject { encodeOciMetadata(this@encodeToJsonString) }

private fun JsonObjectStringBuilder.encodeOciMetadata(metadata: OciMetadata) {
    addString("imageReference", metadata.imageReference.toString())
    addStringIfNotNull("creationTime", metadata.creationTime?.toString())
    addStringIfNotNull("author", metadata.author)
    addStringIfNotNull("user", metadata.user)
    addArrayIfNotEmpty("ports", metadata.ports)
    addObjectIfNotEmpty("environment", metadata.environment)
    addArrayIfNotNull("entryPoint", metadata.entryPoint)
    addArrayIfNotNull("arguments", metadata.arguments)
    addArrayIfNotEmpty("volumes", metadata.volumes)
    addStringIfNotNull("workingDirectory", metadata.workingDirectory)
    addStringIfNotNull("stopSignal", metadata.stopSignal)
    addObjectIfNotEmpty("configAnnotations", metadata.configAnnotations)
    addObjectIfNotEmpty("configDescriptorAnnotations", metadata.configDescriptorAnnotations)
    addObjectIfNotEmpty("manifestAnnotations", metadata.manifestAnnotations)
    addObjectIfNotEmpty("manifestDescriptorAnnotations", metadata.manifestDescriptorAnnotations)
    addObjectIfNotEmpty("indexAnnotations", metadata.indexAnnotations)
    addArrayIfNotEmpty("layers", metadata.layers) { addObject { encodeLayer(it) } }
}

private fun JsonObjectStringBuilder.encodeLayer(layer: OciMetadata.Layer) {
    layer.descriptor?.let { descriptor ->
        addObject("descriptor") { encodeLayerDescriptor(descriptor) }
    }
    addStringIfNotNull("creationTime", layer.creationTime?.toString())
    addStringIfNotNull("author", layer.author)
    addStringIfNotNull("createdBy", layer.createdBy)
    addStringIfNotNull("comment", layer.comment)
}

private fun JsonObjectStringBuilder.encodeLayerDescriptor(descriptor: OciMetadata.Layer.Descriptor) {
    if (descriptor.mediaType != GZIP_COMPRESSED_LAYER_MEDIA_TYPE) {
        addString("mediaType", descriptor.mediaType)
    }
    addString("digest", descriptor.digest.toString())
    addNumber("size", descriptor.size)
    addString("diffId", descriptor.diffId.toString())
    addObjectIfNotEmpty("annotations", descriptor.annotations)
}

internal fun String.decodeAsJsonToOciMetadata() = jsonObject(this).decodeOciMetadata()

private fun JsonObject.decodeOciMetadata() = OciMetadata(
    get("imageReference") { asString().toOciImageReference() },
    getInstantOrNull("creationTime"),
    getStringOrNull("author"),
    getStringOrNull("user"),
    getStringSetOrEmpty("ports"),
    getStringMapOrEmpty("environment"),
    getStringListOrNull("entryPoint"),
    getStringListOrNull("arguments"),
    getStringSetOrEmpty("volumes"),
    getStringOrNull("workingDirectory"),
    getStringOrNull("stopSignal"),
    getStringMapOrEmpty("configAnnotations"),
    getStringMapOrEmpty("configDescriptorAnnotations"),
    getStringMapOrEmpty("manifestAnnotations"),
    getStringMapOrEmpty("manifestDescriptorAnnotations"),
    getStringMapOrEmpty("indexAnnotations"),
    getOrNull("layers") { asArray().toList { asObject().decodeLayer() } } ?: emptyList(),
)

private fun JsonObject.decodeLayer() = OciMetadata.Layer(
    getOrNull("descriptor") { asObject().decodeLayerDescriptor() },
    getInstantOrNull("creationTime"),
    getStringOrNull("author"),
    getStringOrNull("createdBy"),
    getStringOrNull("comment"),
)

private fun JsonObject.decodeLayerDescriptor() = OciMetadata.Layer.Descriptor(
    getStringOrNull("metadata") ?: GZIP_COMPRESSED_LAYER_MEDIA_TYPE,
    getOciDigest("digest"),
    getLong("size"),
    getOciDigest("diffId"),
    getStringMapOrEmpty("annotations"),
)
