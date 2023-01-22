package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.platform.Platform

fun encodeComponent2(component: OciComponent) = jsonObject {
    it.addKey("capabilities").addArray(component.capabilities, JsonValueStringBuilder::encodeCapability)
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> it.addKey("bundle").encodeBundle(bundleOrPlatformBundles)
        is OciComponent.PlatformBundles -> it.addKey("platformBundles").encodePlatformBundles(bundleOrPlatformBundles)
    }
    it.addKeyAndObjectIfNotEmpty("indexAnnotations", component.indexAnnotations)
}

private fun JsonValueStringBuilder.encodeCapability(component: OciComponent.Capability) = addObject {
    it.addKey("group").addString(component.group)
    it.addKey("name").addString(component.name)
}

private fun JsonValueStringBuilder.encodePlatformBundles(platformBundles: OciComponent.PlatformBundles) = addArray {
    for (platformBundle in platformBundles.map) {
        it.addObject { entryObject ->
            entryObject.addKey("platform").encodePlatform(platformBundle.key)
            entryObject.addKey("bundle").encodeBundle(platformBundle.value)
        }
    }
}

private fun JsonValueStringBuilder.encodePlatform(platform: Platform) = addObject {
    it.addKey("os").addString(platform.os)
    it.addKey("architecture").addString(platform.architecture)
    it.addKeyAndStringIfNotEmpty("variant", platform.variant)
    it.addKeyAndStringIfNotEmpty("osVersion", platform.osVersion)
    it.addKeyAndArrayIfNotEmpty("osFeatures", platform.osFeatures)
}

private fun JsonValueStringBuilder.encodeBundle(bundle: OciComponent.Bundle) = addObject {
    it.addKeyAndStringIfNotNull("creationTime", bundle.creationTime?.run { toString() })
    it.addKeyAndStringIfNotNull("author", bundle.author)
    it.addKeyAndStringIfNotNull("user", bundle.user)
    it.addKeyAndArrayIfNotEmpty("ports", bundle.ports)
    it.addKeyAndObjectIfNotEmpty("environment", bundle.environment)
    it.addKeyAndValueIfNotNull("command", bundle.command, JsonValueStringBuilder::encodeCommand)
    it.addKeyAndArrayIfNotEmpty("volumes", bundle.volumes)
    it.addKeyAndStringIfNotNull("workingDirectory", bundle.workingDirectory)
    it.addKeyAndStringIfNotNull("stopSignal", bundle.stopSignal)
    it.addKeyAndObjectIfNotEmpty("configAnnotations", bundle.configAnnotations)
    it.addKeyAndObjectIfNotEmpty("configDescriptorAnnotations", bundle.configDescriptorAnnotations)
    it.addKeyAndObjectIfNotEmpty("manifestAnnotations", bundle.manifestAnnotations)
    it.addKeyAndObjectIfNotEmpty("manifestDescriptorAnnotations", bundle.manifestDescriptorAnnotations)
    it.addKeyAndArrayIfNotEmpty("parentCapabilities", bundle.parentCapabilities, JsonValueStringBuilder::encodeCapability)
    it.addKeyAndArrayIfNotEmpty("layers", bundle.layers, JsonValueStringBuilder::encodeLayer)
}

private fun JsonValueStringBuilder.encodeCommand(command: OciComponent.Bundle.Command) = addObject {
    it.addKeyAndArrayIfNotNull("entryPoint", command.entryPoint)
    it.addKey("arguments").addArray(command.arguments)
}

private fun JsonValueStringBuilder.encodeLayer(layer: OciComponent.Bundle.Layer) = addObject {
    if (layer.descriptor != null) {
        it.addKey("digest").addString(layer.descriptor.digest)
        it.addKey("diffId").addString(layer.descriptor.diffId)
        it.addKey("size").addNumber(layer.descriptor.size)
        it.addKeyAndObjectIfNotEmpty("annotations", layer.descriptor.annotations)
    }
    it.addKeyAndStringIfNotNull("creationTime", layer.creationTime?.run { toString() })
    it.addKeyAndStringIfNotNull("author", layer.author)
    it.addKeyAndStringIfNotNull("createdBy", layer.createdBy)
    it.addKeyAndStringIfNotNull("comment", layer.comment)
}