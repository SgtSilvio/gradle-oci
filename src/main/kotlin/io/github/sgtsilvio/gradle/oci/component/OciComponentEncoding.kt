package io.github.sgtsilvio.gradle.oci.component

import org.json.JSONArray
import org.json.JSONObject

fun encodeComponent(component: OciComponent) = JSONObject().apply {
    put("capabilities", encodeCapabilities(component.capabilities))
    put("prebuiltIndexDigest", component.prebuiltIndexDigest)
    when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
        is OciComponent.Bundle -> put("bundle", encodeBundle(bundleOrPlatformBundles))
        is OciComponent.PlatformBundles -> put("platformBundles", encodePlatformBundles(bundleOrPlatformBundles))
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

private fun encodePlatform(platform: OciComponent.Platform) = JSONObject().apply {
    put("architecture", platform.architecture)
    put("os", platform.os)
    put("osVersion", platform.osVersion)
    if (platform.osFeatures.isNotEmpty()) {
        put("osFeatures", platform.osFeatures)
    }
    put("variant", platform.variant)
}

private fun encodeBundle(bundle: OciComponent.Bundle) = JSONObject().apply {
    put("prebuiltManifestDigest", bundle.prebuiltManifestDigest)
    put("prebuiltConfigDigest", bundle.prebuiltConfigDigest)
    put("creationTime", bundle.creationTime?.run { toString() })
    put("author", bundle.author)
    put("user", bundle.user)
    if (bundle.ports.isNotEmpty()) {
        put("ports", bundle.ports) // TODO sorted
    }
    if (bundle.environment.isNotEmpty()) {
        put("environment", bundle.environment)
    }
    if (bundle.entryPoint != null) {
        put("entryPoint", bundle.entryPoint)
    }
    if (bundle.arguments != null) {
        put("arguments", bundle.arguments)
    }
    if (bundle.volumes.isNotEmpty()) {
        put("volumes", bundle.volumes) // TODO sorted
    }
    put("workingDirectory", bundle.workingDirectory)
    put("stopSignal", bundle.stopSignal)
    if (bundle.annotations.isNotEmpty()) {
        put("annotations", bundle.annotations)
    }
    if (bundle.parentCapabilities.isNotEmpty()) {
        put("parentCapabilities", encodeParentCapabilities(bundle.parentCapabilities))
    }
    put("layers", encodeLayers(bundle.layers))
}

private fun encodeParentCapabilities(parentCapabilities: List<Set<OciComponent.Capability>>) = JSONArray().apply {
    for (singleParentCapabilities in parentCapabilities) {
        put(encodeCapabilities(singleParentCapabilities))
    }
}

private fun encodeLayers(layers: List<OciComponent.Bundle.Layer>) = JSONArray().apply {
    for (layer in layers) {
        put(encodeLayer(layer))
    }
}

private fun encodeLayer(layer: OciComponent.Bundle.Layer) = JSONObject().apply {
    put("digest", layer.digest)
    put("diffId", layer.diffId)
    put("creationTime", layer.creationTime?.run { toString() })
    put("author", layer.author)
    put("createdBy", layer.createdBy)
    put("comment", layer.comment)
}