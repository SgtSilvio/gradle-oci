package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.platform.Platform

fun encodeComponent(component: OciComponent) = jsonObject {
    addKey("capabilities").addArray { component.capabilities.forEach(::encodeCapability) }
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> addKey("bundle").encodeBundle(bundleOrPlatformBundles)
        is OciComponent.PlatformBundles -> addKey("platformBundles").encodePlatformBundles(bundleOrPlatformBundles)
    }
    addKeyAndObjectIfNotEmpty("indexAnnotations", component.indexAnnotations)
}

private fun JsonValueStringBuilder.encodeCapability(component: OciComponent.Capability) = addObject {
    addKey("group").addString(component.group)
    addKey("name").addString(component.name)
}

private fun JsonValueStringBuilder.encodePlatformBundles(platformBundles: OciComponent.PlatformBundles) = addArray {
    for (platformBundle in platformBundles.map) {
        addObject {
            addKey("platform").encodePlatform(platformBundle.key)
            addKey("bundle").encodeBundle(platformBundle.value)
        }
    }
}

private fun JsonValueStringBuilder.encodePlatform(platform: Platform) = addObject {
    addKey("os").addString(platform.os)
    addKey("architecture").addString(platform.architecture)
    addKeyAndStringIfNotEmpty("variant", platform.variant)
    addKeyAndStringIfNotEmpty("osVersion", platform.osVersion)
    addKeyAndArrayIfNotEmpty("osFeatures", platform.osFeatures)
}

private fun JsonValueStringBuilder.encodeBundle(bundle: OciComponent.Bundle) = addObject {
    addKeyAndStringIfNotNull("creationTime", bundle.creationTime?.run { toString() })
    addKeyAndStringIfNotNull("author", bundle.author)
    addKeyAndStringIfNotNull("user", bundle.user)
    addKeyAndArrayIfNotEmpty("ports", bundle.ports)
    addKeyAndObjectIfNotEmpty("environment", bundle.environment)
    addKeyAndValueIfNotNull("command", bundle.command, JsonValueStringBuilder::encodeCommand)
    addKeyAndArrayIfNotEmpty("volumes", bundle.volumes)
    addKeyAndStringIfNotNull("workingDirectory", bundle.workingDirectory)
    addKeyAndStringIfNotNull("stopSignal", bundle.stopSignal)
    addKeyAndObjectIfNotEmpty("configAnnotations", bundle.configAnnotations)
    addKeyAndObjectIfNotEmpty("configDescriptorAnnotations", bundle.configDescriptorAnnotations)
    addKeyAndObjectIfNotEmpty("manifestAnnotations", bundle.manifestAnnotations)
    addKeyAndObjectIfNotEmpty("manifestDescriptorAnnotations", bundle.manifestDescriptorAnnotations)
    addKeyAndArrayIfNotEmpty("parentCapabilities", bundle.parentCapabilities, JsonValueStringBuilder::encodeCapability)
    addKeyAndArrayIfNotEmpty("layers", bundle.layers, JsonValueStringBuilder::encodeLayer)
}

private fun JsonValueStringBuilder.encodeCommand(command: OciComponent.Bundle.Command) = addObject {
    addKeyAndArrayIfNotNull("entryPoint", command.entryPoint)
    addKey("arguments").addArray(command.arguments)
}

private fun JsonValueStringBuilder.encodeLayer(layer: OciComponent.Bundle.Layer) = addObject {
    if (layer.descriptor != null) {
        addKey("digest").addString(layer.descriptor.digest)
        addKey("diffId").addString(layer.descriptor.diffId)
        addKey("size").addNumber(layer.descriptor.size)
        addKeyAndObjectIfNotEmpty("annotations", layer.descriptor.annotations)
    }
    addKeyAndStringIfNotNull("creationTime", layer.creationTime?.run { toString() })
    addKeyAndStringIfNotNull("author", layer.author)
    addKeyAndStringIfNotNull("createdBy", layer.createdBy)
    addKeyAndStringIfNotNull("comment", layer.comment)
}