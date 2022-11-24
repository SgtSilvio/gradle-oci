package io.github.sgtsilvio.gradle.oci.component

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun decodeComponent(string: String) = decodeComponent(JSONObject(string))

private fun decodeComponent(jsonObject: JSONObject) = OciComponent(
    decodeCapabilities(jsonObject.opt("capabilities")),
    jsonObject.optString("prebuiltIndexDigest", null),
    decodeBundleOrPlatformBundles(jsonObject)
)

private fun decodeCapabilities(jsonArray: Any?): Set<OciComponent.Capability> {
    if (jsonArray !is JSONArray) {
        throw IllegalArgumentException("'capabilities' must be an array, but was '$jsonArray'")
    }
    return decodeCapabilities(jsonArray)
}

private fun decodeCapabilities(jsonArray: JSONArray): Set<OciComponent.Capability> =
    jsonArray.mapIndexedTo(mutableSetOf(), ::decodeCapability)

private fun decodeCapability(index: Int, jsonObject: Any): OciComponent.Capability {
    if (jsonObject !is JSONObject) {
        throw IllegalArgumentException("'capabilities[$index]' must be an object, but was '$jsonObject'")
    }
    return decodeCapability(jsonObject)
}

private fun decodeCapability(jsonObject: JSONObject) = OciComponent.Capability(
    jsonObject.getString("group"),
    jsonObject.getString("name"),
)

private fun decodeBundleOrPlatformBundles(jsonObject: JSONObject) = if (jsonObject.has("bundle")) {
    if (jsonObject.has("platformBundles")) {
        throw IllegalStateException("'bundle' and 'platformBundles' must not both be present")
    }
    decodeBundle(jsonObject.opt("bundle"))
} else {
    decodePlatformBundles(jsonObject.opt("platformBundles"))
}

private fun decodePlatformBundles(jsonArray: Any?): OciComponent.PlatformBundles {
    if (jsonArray !is JSONArray) {
        throw IllegalArgumentException("'platformBundles' must be an array, but was '$jsonArray'")
    }
    var index = 0
    return OciComponent.PlatformBundles(jsonArray.associate { decodePlatformBundle(it, index++) })
}

private fun decodePlatformBundle(jsonObject: Any?, index: Int): Pair<OciComponent.Platform, OciComponent.Bundle> {
    if (jsonObject !is JSONObject) {
        throw IllegalArgumentException("'platformBundles[$index]' must be an object, but was '$jsonObject'")
    }
    return Pair(
        decodePlatform(jsonObject.opt("platform")), // TODO error message misses platformBundles[$index]
        decodeBundle(jsonObject.opt("bundle")), // TODO error message misses platformBundles[$index]
    )
}

private fun decodePlatform(jsonObject: Any?): OciComponent.Platform {
    if (jsonObject !is JSONObject) {
        throw IllegalArgumentException("'platform' must be an object, but was '$jsonObject'")
    }
    return decodePlatform(jsonObject)
}

private fun decodePlatform(jsonObject: JSONObject) = OciComponent.Platform(
    jsonObject.getString("architecture"),
    jsonObject.getString("os"),
    jsonObject.optString("osVersion", null),
    jsonObject.optJSONArray("osFeatures")?.map { it as String } ?: listOf(),
    jsonObject.optString("variant", null),
)

private fun decodeBundle(jsonObject: Any?): OciComponent.Bundle {
    if (jsonObject !is JSONObject) {
        throw IllegalArgumentException("'bundle' must be an object, but was '$jsonObject'")
    }
    return decodeBundle(jsonObject)
}

private fun decodeBundle(jsonObject: JSONObject) = OciComponent.Bundle(
    jsonObject.optString("prebuiltManifestDigest", null),
    jsonObject.optString("prebuiltConfigDigest", null),
    jsonObject.optString("creationTime", null)?.let(Instant::parse),
    jsonObject.optString("author", null),
    jsonObject.optString("user", null),
    jsonObject.optJSONArray("ports")?.mapTo(mutableSetOf()) { it as String } ?: setOf(),
    jsonObject.optJSONObject("environment")?.toMap()?.mapValues { it.value as String } ?: mapOf(),
    jsonObject.optJSONArray("entryPoint")?.map { it as String },
    jsonObject.optJSONArray("arguments")?.map { it as String },
    jsonObject.optJSONArray("volumes")?.mapTo(mutableSetOf()) { it as String } ?: setOf(),
    jsonObject.optString("workingDirectory", null),
    jsonObject.optString("stopSignal", null),
    jsonObject.optJSONObject("annotations")?.toMap()?.mapValues { it.value as String } ?: mapOf(),
    jsonObject.optJSONArray("baseComponents")?.map { decodeCapabilities(it as JSONArray) } ?: listOf(),
    jsonObject.getJSONArray("layers").map(::decodeLayer),
)

private fun decodeLayer(any: Any) = decodeLayer(any as JSONObject)

private fun decodeLayer(jsonObject: JSONObject) = OciComponent.Bundle.Layer(
    jsonObject.getString("digest"),
    jsonObject.getString("diffId"),
    jsonObject.optString("creationTime", null)?.let(Instant::parse),
    jsonObject.optString("author", null),
    jsonObject.optString("createdBy", null),
    jsonObject.optString("comment", null),
)

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
    if (bundle.baseComponents.isNotEmpty()) {
        put("baseComponents", encodeBaseComponents(bundle.baseComponents))
    }
    put("layers", encodeLayers(bundle.layers))
}

private fun encodeBaseComponents(baseComponents: List<Set<OciComponent.Capability>>) = JSONArray().apply {
    for (baseComponent in baseComponents) {
        put(encodeCapabilities(baseComponent))
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

fun main() {
//    val string = """
//    {
//        "capabilities": [
//            {
//                "group": "org.example",
//                "name": "test"
//            },
//            {
//                "group": "com.example",
//                "name": "test"
//            }
//        ],
//        "prebuiltIndexDigest": "sha256:123",
//        "platformBundles": [
//            {
//                "platform": {
//                    "architecture": "arm64",
//                    "os": "linux",
//                    "variant": "v8"
//                },
//                "bundle": {
//                    "prebuiltManifestDigest": "sha256:234",
//                    "prebuiltConfigDigest": "sha256:345",
//                    "creationTime": "${Instant.EPOCH}",
//                    "author": "John Doe",
//                    "user": "example",
//                    "ports": [
//                        "80/tcp",
//                        "443/tcp"
//                    ],
//                    "environment": {
//                        "HOME": "/",
//                        "ENV": "test"
//                    },
//                    "entryPoint": ["bin/sh", "-c"],
//                    "arguments": ["echo", "hello"],
//                    "volumes": [
//                        "/data",
//                        "/log"
//                    ],
//                    "workingDirectory": "/home/example",
//                    "stopSignal": "SIGKILL",
//                    "annotations": {
//                        "hey": "ho",
//                        "aso": "wabern"
//                    },
//                    "baseComponents": [
//                        [
//                            {
//                                "group": "org.example",
//                                "name": "base"
//                            }
//                        ],
//                        [
//                            {
//                                "group": "org.example",
//                                "name": "base2"
//                            }
//                        ]
//                    ],
//                    "layers": [
//                        {
//                            "digest": "sha256:456",
//                            "diffId": "sha256:567",
//                            "creationTime": "${Instant.EPOCH}",
//                            "author": "John Doe",
//                            "createdBy": "hand",
//                            "comment": "ja"
//                        },
//                        {
//                            "digest": "sha256:678",
//                            "diffId": "sha256:789"
//                        }
//                    ]
//                }
//            },
//            {
//                "platform": {
//                    "architecture": "amd64",
//                    "os": "linux"
//                },
//                "bundle": {
//                    "layers": [
//                        {
//                            "digest": "sha256:456",
//                            "diffId": "sha256:567"
//                        }
//                    ]
//                }
//            }
//        ]
//    }
//    """.trimIndent()
    val string = """
    {
        "capabilities": [
            {
                "group": "org.example",
                "name": "test"
            }
        ],
        "bundle": {
            "layers": [
                {
                    "digest": "sha256:456",
                    "diffId": "sha256:567"
                }
            ]
        }
    }
    """.trimIndent()
    val ociComponent = decodeComponent(string)
    println(encodeComponent(ociComponent).toString(2))
}