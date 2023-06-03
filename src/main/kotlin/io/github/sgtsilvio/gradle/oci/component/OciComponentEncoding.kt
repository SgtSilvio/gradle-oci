package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.json.*
import io.github.sgtsilvio.gradle.oci.metadata.LAYER_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.platform.Platform

fun OciComponent.encodeToJsonString() = jsonObject { encodeOciComponent(this@encodeToJsonString) }

private fun JsonObjectStringBuilder.encodeOciComponent(component: OciComponent) {
    addString("imageReference", component.imageReference.toString())
    addArray("capabilities", component.capabilities) { addObject { encodeVersionedCoordinates(it) } }
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> addObject("bundle") { encodeBundle(bundleOrPlatformBundles) }
        is OciComponent.PlatformBundles -> addArray("platformBundles") { encodePlatformBundles(bundleOrPlatformBundles) }
    }
    addObjectIfNotEmpty("indexAnnotations", component.indexAnnotations)
}

private fun JsonArrayStringBuilder.encodePlatformBundles(platformBundles: OciComponent.PlatformBundles) {
    for ((platform, bundle) in platformBundles.map) {
        addObject {
            addObject("platform") { encodePlatform(platform) }
            addObject("bundle") { encodeBundle(bundle) }
        }
    }
}

private fun JsonObjectStringBuilder.encodePlatform(platform: Platform) {
    addString("os", platform.os)
    addString("architecture", platform.architecture)
    addStringIfNotEmpty("variant", platform.variant)
    addStringIfNotEmpty("osVersion", platform.osVersion)
    addArrayIfNotEmpty("osFeatures", platform.osFeatures)
}

private fun JsonObjectStringBuilder.encodeBundle(bundle: OciComponent.Bundle) {
    addStringIfNotNull("creationTime", bundle.creationTime?.toString())
    addStringIfNotNull("author", bundle.author)
    addStringIfNotNull("user", bundle.user)
    addArrayIfNotEmpty("ports", bundle.ports)
    addObjectIfNotEmpty("environment", bundle.environment)
    bundle.command?.let { command ->
        addObject("command") { encodeCommand(command) }
    }
    addArrayIfNotEmpty("volumes", bundle.volumes)
    addStringIfNotNull("workingDirectory", bundle.workingDirectory)
    addStringIfNotNull("stopSignal", bundle.stopSignal)
    addObjectIfNotEmpty("configAnnotations", bundle.configAnnotations)
    addObjectIfNotEmpty("configDescriptorAnnotations", bundle.configDescriptorAnnotations)
    addObjectIfNotEmpty("manifestAnnotations", bundle.manifestAnnotations)
    addObjectIfNotEmpty("manifestDescriptorAnnotations", bundle.manifestDescriptorAnnotations)
    addArrayIfNotEmpty("parentCapabilities", bundle.parentCapabilities) { addObject { encodeCoordinates(it) } }
    addArrayIfNotEmpty("layers", bundle.layers) { addObject { encodeLayer(it) } }
}

private fun JsonObjectStringBuilder.encodeCommand(command: OciComponent.Bundle.Command) {
    addArrayIfNotNull("entryPoint", command.entryPoint)
    addArray("arguments", command.arguments)
}

private fun JsonObjectStringBuilder.encodeLayer(layer: OciComponent.Bundle.Layer) {
    layer.descriptor?.let { descriptor ->
        addObject("descriptor") { encodeLayerDescriptor(descriptor) }
    }
    addStringIfNotNull("creationTime", layer.creationTime?.toString())
    addStringIfNotNull("author", layer.author)
    addStringIfNotNull("createdBy", layer.createdBy)
    addStringIfNotNull("comment", layer.comment)
}

private fun JsonObjectStringBuilder.encodeLayerDescriptor(descriptor: OciComponent.Bundle.Layer.Descriptor) {
    if (descriptor.mediaType != LAYER_MEDIA_TYPE) {
        addString("mediaType", descriptor.mediaType)
    }
    addString("digest", descriptor.digest.toString())
    addNumber("size", descriptor.size)
    addString("diffId", descriptor.diffId.toString())
    addObjectIfNotEmpty("annotations", descriptor.annotations)
}