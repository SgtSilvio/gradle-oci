package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.*
import io.github.sgtsilvio.gradle.oci.platform.Platform

fun encodeComponent(component: OciComponent) = jsonObject { encodeComponent(component) }

private fun JsonObjectStringBuilder.encodeComponent(component: OciComponent) {
    addArray("capabilities", component.capabilities) { addObject { encodeCapability(it) } }
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> addObject("bundle") { encodeBundle(bundleOrPlatformBundles) }
        is OciComponent.PlatformBundles -> addArray("platformBundles") { encodePlatformBundles(bundleOrPlatformBundles) }
    }
    addObjectIfNotEmpty("indexAnnotations", component.indexAnnotations)
}

private fun JsonObjectStringBuilder.encodeCapability(component: OciComponent.Capability) {
    addString("group", component.group)
    addString("name", component.name)
}

private fun JsonArrayStringBuilder.encodePlatformBundles(platformBundles: OciComponent.PlatformBundles) {
    for (platformBundle in platformBundles.map) {
        addObject {
            addObject("platform") { encodePlatform(platformBundle.key) }
            addObject("bundle") { encodeBundle(platformBundle.value) }
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
    if (bundle.command != null) {
        addObject("command") { encodeCommand(bundle.command) }
    }
    addArrayIfNotEmpty("volumes", bundle.volumes)
    addStringIfNotNull("workingDirectory", bundle.workingDirectory)
    addStringIfNotNull("stopSignal", bundle.stopSignal)
    addObjectIfNotEmpty("configAnnotations", bundle.configAnnotations)
    addObjectIfNotEmpty("configDescriptorAnnotations", bundle.configDescriptorAnnotations)
    addObjectIfNotEmpty("manifestAnnotations", bundle.manifestAnnotations)
    addObjectIfNotEmpty("manifestDescriptorAnnotations", bundle.manifestDescriptorAnnotations)
    addArrayIfNotEmpty("parentCapabilities", bundle.parentCapabilities) { addObject { encodeCapability(it) } }
    addArrayIfNotEmpty("layers", bundle.layers) { addObject { encodeLayer(it) } }
}

private fun JsonObjectStringBuilder.encodeCommand(command: OciComponent.Bundle.Command) {
    addArrayIfNotNull("entryPoint", command.entryPoint)
    addArray("arguments", command.arguments)
}

private fun JsonObjectStringBuilder.encodeLayer(layer: OciComponent.Bundle.Layer) {
    if (layer.descriptor != null) {
        addString("digest", layer.descriptor.digest)
        addString("diffId", layer.descriptor.diffId)
        addNumber("size", layer.descriptor.size)
        addObjectIfNotEmpty("annotations", layer.descriptor.annotations)
    }
    addStringIfNotNull("creationTime", layer.creationTime?.toString())
    addStringIfNotNull("author", layer.author)
    addStringIfNotNull("createdBy", layer.createdBy)
    addStringIfNotNull("comment", layer.comment)
}