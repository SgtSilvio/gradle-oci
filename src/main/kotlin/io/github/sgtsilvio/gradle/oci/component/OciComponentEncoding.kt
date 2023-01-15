package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.dsl.Platform
import org.json.JSONArray
import org.json.JSONObject

fun encodeComponent(component: OciComponent) = JSONObject().apply {
    put("capabilities", encodeCapabilities(component.capabilities))
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> put("bundle", encodeBundle(bundleOrPlatformBundles))
        is OciComponent.PlatformBundles -> put("platformBundles", encodePlatformBundles(bundleOrPlatformBundles))
    }
    if (component.indexAnnotations.isNotEmpty()) {
        put("indexAnnotations", component.indexAnnotations) // TODO sorted
    }
}

private fun encodeCapabilities(capabilities: Set<OciComponent.Capability>) = JSONArray().apply {
    for (capability in capabilities) { // TODO sorted
        put(encodeCapability(capability))
    }
}

private fun encodeCapability(component: OciComponent.Capability) = JSONObject().apply {
    put("group", component.group)
    put("name", component.name)
}

private fun encodePlatformBundles(platformBundles: OciComponent.PlatformBundles) = JSONArray().apply {
    for (platformBundle in platformBundles.map) {
        put(JSONObject().apply {
            put("platform", encodePlatform(platformBundle.key))
            put("bundle", encodeBundle(platformBundle.value))
        })
    }
}

private fun encodePlatform(platform: Platform) = JSONObject().apply {
    put("os", platform.os)
    put("architecture", platform.architecture)
    if (platform.variant != "") {
        put("variant", platform.variant)
    }
    if (platform.osVersion != "") {
        put("osVersion", platform.osVersion)
    }
    if (platform.osFeatures.isNotEmpty()) {
        put("osFeatures", platform.osFeatures) // TODO sorted
    }
}

private fun encodeBundle(bundle: OciComponent.Bundle) = JSONObject().apply {
    put("creationTime", bundle.creationTime?.run { toString() })
    put("author", bundle.author)
    put("user", bundle.user)
    if (bundle.ports.isNotEmpty()) {
        put("ports", bundle.ports) // TODO sorted
    }
    if (bundle.environment.isNotEmpty()) {
        put("environment", bundle.environment) // TODO sorted
    }
    if (bundle.command != null) {
        put("command", encodeCommand(bundle.command))
    }
    if (bundle.volumes.isNotEmpty()) {
        put("volumes", bundle.volumes) // TODO sorted
    }
    put("workingDirectory", bundle.workingDirectory)
    put("stopSignal", bundle.stopSignal)
    if (bundle.configAnnotations.isNotEmpty()) {
        put("configAnnotations", bundle.configAnnotations) // TODO sorted
    }
    if (bundle.configDescriptorAnnotations.isNotEmpty()) {
        put("configDescriptorAnnotations", bundle.configDescriptorAnnotations) // TODO sorted
    }
    if (bundle.manifestAnnotations.isNotEmpty()) {
        put("manifestAnnotations", bundle.manifestAnnotations) // TODO sorted
    }
    if (bundle.manifestDescriptorAnnotations.isNotEmpty()) {
        put("manifestDescriptorAnnotations", bundle.manifestDescriptorAnnotations) // TODO sorted
    }
    if (bundle.parentCapabilities.isNotEmpty()) {
        put("parentCapabilities", encodeParentCapabilities(bundle.parentCapabilities))
    }
    put("layers", encodeLayers(bundle.layers))
}

private fun encodeCommand(command: OciComponent.Bundle.Command) = JSONObject().apply {
    if (command.entryPoint != null) {
        put("entryPoint", command.entryPoint)
    }
    put("arguments", command.arguments)
}

private fun encodeParentCapabilities(parentCapabilities: List<OciComponent.Capability>) = JSONArray().apply {
    for (parentCapability in parentCapabilities) {
        put(encodeCapability(parentCapability))
    }
}

private fun encodeLayers(layers: List<OciComponent.Bundle.Layer>) = JSONArray().apply {
    for (layer in layers) {
        put(encodeLayer(layer))
    }
}

private fun encodeLayer(layer: OciComponent.Bundle.Layer) = JSONObject().apply {
    if (layer.descriptor != null) {
        put("digest", layer.descriptor.digest)
        put("diffId", layer.descriptor.diffId)
        put("size", layer.descriptor.size)
        if (layer.descriptor.annotations.isNotEmpty()) {
            put("annotations", layer.descriptor.annotations) // TODO sorted
        }
    }
    put("creationTime", layer.creationTime?.run { toString() })
    put("author", layer.author)
    put("createdBy", layer.createdBy)
    put("comment", layer.comment)
}