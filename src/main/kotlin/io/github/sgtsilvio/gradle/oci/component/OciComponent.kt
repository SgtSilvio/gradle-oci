package io.github.sgtsilvio.gradle.oci.component

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * @author Silvio Giebl
 */
data class OciComponent(val component: Component, val prebuiltIndexDigest: String?, val bundles: List<Bundle>) {
    // TODO platformToBundles map?
    // TODO sealed interface for MultiPlatformBundle | Bundles "union type"

    data class Component(val group: String, val name: String) // TODO rename to ComponentId?

    data class Bundle(
        val prebuiltManifestDigest: String?,
        val prebuiltConfigDigest: String?,
        val platform: Platform,
        val config: Config, //? not null, empty == null
        val baseImage: Component?, // TODO name? baseComponent?
        val layers: List<Layer>,
    ) {

        data class Platform(
            val architecture: String,
            val os: String,
            val osVersion: String?,
            val osFeatures: List<String>, //? not null, empty == null
            val variant: String?,
        )

        data class Config(
            val creationTime: Instant?,
            val author: String?,
            val user: String?,
            val ports: Set<String>, //? not null, empty == null // TODO sorted
            val environment: Map<String, String>, //? not null, empty == null
            val entryPoint: List<String>?, // empty (no args) is different from null (not set)
            val arguments: List<String>?, // empty (no args) is different from null (not set)
            val volumes: Set<String>, //? not null, empty == null // TODO sorted
            val workingDirectory: String?,
            val stopSignal: String?,
            val annotations: Map<String, String>, //? not null, empty == null
        )

        interface Layer

        data class InternalLayer(
            val creationTime: Instant?,
            val author: String?,
            val createdBy: String?,
            val comment: String?,
            val file: File?,
        ) : Layer {

            data class File(val digest: String, val diffId: String)
        }

        data class ExternalLayer(val component: Component) : Layer
    }
}

private val EMPTY_CONFIG =
    OciComponent.Bundle.Config(null, null, null, setOf(), mapOf(), null, null, setOf(), null, null, mapOf())

fun decode(string: String) = decode(JSONObject(string))

private fun decode(jsonObject: JSONObject) = OciComponent(
    jsonObject.getJSONObject("component").let(::decodeComponent),
    jsonObject.optString("prebuiltIndexDigest", null),
    jsonObject.getJSONArray("bundles").map(::decodeBundle),
)

private fun decodeComponent(jsonObject: JSONObject) = OciComponent.Component(
    jsonObject.getString("group"),
    jsonObject.getString("name"),
)

private fun decodeBundle(any: Any) = decodeBundle(any as JSONObject)

private fun decodeBundle(jsonObject: JSONObject) = OciComponent.Bundle(
    jsonObject.optString("prebuiltManifestDigest", null),
    jsonObject.optString("prebuiltConfigDigest", null),
    jsonObject.getJSONObject("platform").let(::decodePlatform),
    jsonObject.optJSONObject("config")?.let(::decodeConfig) ?: EMPTY_CONFIG,
    jsonObject.optJSONObject("baseImage")?.let(::decodeComponent),
    jsonObject.getJSONArray("layers").map(::decodeLayer),
)

private fun decodePlatform(jsonObject: JSONObject) = OciComponent.Bundle.Platform(
    jsonObject.getString("architecture"),
    jsonObject.getString("os"),
    jsonObject.optString("osVersion", null),
    jsonObject.optJSONArray("osFeatures")?.map { it as String } ?: listOf(),
    jsonObject.optString("variant", null),
)

private fun decodeConfig(jsonObject: JSONObject) = OciComponent.Bundle.Config(
    jsonObject.optString("creationTime", null)?.let(Instant::parse),
    jsonObject.optString("author", null),
    jsonObject.optString("user", null),
    jsonObject.optJSONArray("ports")?.map { it as String }?.toSet() ?: setOf(),
    jsonObject.optJSONObject("environment")?.toMap()?.mapValues { it.value as String } ?: mapOf(),
    jsonObject.optJSONArray("entryPoint")?.map { it as String },
    jsonObject.optJSONArray("arguments")?.map { it as String },
    jsonObject.optJSONArray("volumes")?.map { it as String }?.toSet() ?: setOf(),
    jsonObject.optString("workingDirectory", null),
    jsonObject.optString("stopSignal", null),
    jsonObject.optJSONObject("annotations")?.toMap()?.mapValues { it.value as String } ?: mapOf(),
)

private fun decodeLayer(any: Any) = decodeLayer(any as JSONObject)

private fun decodeLayer(jsonObject: JSONObject): OciComponent.Bundle.Layer = if (jsonObject.has("component")) {
    decodeExternalLayer(jsonObject)
} else {
    decodeInternalLayer(jsonObject)
}

private fun decodeInternalLayer(jsonObject: JSONObject) = OciComponent.Bundle.InternalLayer(
    jsonObject.optString("creationTime", null)?.let(Instant::parse),
    jsonObject.optString("author", null),
    jsonObject.optString("createdBy", null),
    jsonObject.optString("comment", null),
    jsonObject.optJSONObject("file")?.let(::decodeFile),
)

private fun decodeFile(jsonObject: JSONObject) = OciComponent.Bundle.InternalLayer.File(
    jsonObject.getString("digest"),
    jsonObject.getString("diffId"),
)

private fun decodeExternalLayer(jsonObject: JSONObject) = OciComponent.Bundle.ExternalLayer(
    jsonObject.getJSONObject("component").let(::decodeComponent),
)

fun encode(ociComponent: OciComponent) = JSONObject().apply {
    put("component", encodeComponent(ociComponent.component))
    put("prebuiltIndexDigest", ociComponent.prebuiltIndexDigest)
    put("bundles", encodeBundles(ociComponent.bundles))
}

private fun encodeComponent(component: OciComponent.Component) = JSONObject().apply {
    put("group", component.group)
    put("name", component.name)
}

private fun encodeBundles(bundles: List<OciComponent.Bundle>) = JSONArray().apply {
    for (bundle in bundles) {
        put(encodeBundle(bundle))
    }
}

private fun encodeBundle(bundle: OciComponent.Bundle) = JSONObject().apply {
    put("prebuiltManifestDigest", bundle.prebuiltManifestDigest)
    put("prebuiltConfigDigest", bundle.prebuiltConfigDigest)
    put("platform", encodePlatform(bundle.platform))
    put("config", encodeConfig(bundle.config))
    put("baseImage", bundle.baseImage?.let { encodeComponent(it) })
    put("layers", encodeLayers(bundle.layers))
}

private fun encodePlatform(platform: OciComponent.Bundle.Platform) = JSONObject().apply {
    put("architecture", platform.architecture)
    put("os", platform.os)
    put("osVersion", platform.osVersion)
    if (platform.osFeatures.isNotEmpty()) {
        put("osFeatures", platform.osFeatures)
    }
    put("variant", platform.variant)
}

private fun encodeConfig(config: OciComponent.Bundle.Config) = if (config == EMPTY_CONFIG) {
    null
} else JSONObject().apply {
    put("creationTime", config.creationTime?.run { toString() })
    put("author", config.author)
    put("user", config.user)
    if (config.ports.isNotEmpty()) {
        put("ports", config.ports)
    }
    if (config.environment.isNotEmpty()) {
        put("environment", config.environment)
    }
    if (config.entryPoint != null) {
        put("entryPoint", config.entryPoint)
    }
    if (config.arguments != null) {
        put("arguments", config.arguments)
    }
    if (config.volumes.isNotEmpty()) {
        put("volumes", config.volumes)
    }
    put("workingDirectory", config.workingDirectory)
    put("stopSignal", config.stopSignal)
    if (config.annotations.isNotEmpty()) {
        put("annotations", config.annotations)
    }
}

private fun encodeLayers(layers: List<OciComponent.Bundle.Layer>) = JSONArray().apply {
    for (layer in layers) {
        put(encodeLayer(layer))
    }
}

private fun encodeLayer(layer: OciComponent.Bundle.Layer) = if (layer is OciComponent.Bundle.InternalLayer) {
    encodeInternalLayer(layer)
} else {
    encodeExternalLayer(layer as OciComponent.Bundle.ExternalLayer)
}

private fun encodeInternalLayer(layer: OciComponent.Bundle.InternalLayer) = JSONObject().apply {
    put("creationTime", layer.creationTime?.run { toString() })
    put("author", layer.author)
    put("createdBy", layer.createdBy)
    put("comment", layer.comment)
    put("file", layer.file?.let { encodeFile(it) })
}

private fun encodeFile(file: OciComponent.Bundle.InternalLayer.File) = JSONObject().apply {
    put("digest", file.digest)
    put("diffId", file.diffId)
}

private fun encodeExternalLayer(layer: OciComponent.Bundle.ExternalLayer) = JSONObject().apply {
    put("component", encodeComponent(layer.component))
}

fun main() {
//    val string = """
//    {
//        "component": {
//            "group": "org.example",
//            "name": "test"
//        },
//        "prebuiltIndexDigest": "sha256:123",
//        "bundles": [
//            {
//                "prebuiltManifestDigest": "sha256:234",
//                "prebuiltConfigDigest": "sha256:345",
//                "platform": {
//                    "architecture": "arm64",
//                    "os": "linux",
//                    "variant": "v8"
//                },
//                "config": {
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
//                    }
//                },
//                "baseImage": {
//                    "group": "org.example",
//                    "name": "base"
//                },
//                "layers": [
//                    {
//                        "creationTime": "${Instant.EPOCH}",
//                        "author": "John Doe",
//                        "createdBy": "hand",
//                        "comment": "ja",
//                        "file": {
//                            "digest": "sha256:456",
//                            "diffId": "sha256:567"
//                        }
//                    },
//                    {
//                        "component": {
//                            "group": "org.example",
//                            "name": "layer"
//                        }
//                    }
//                ]
//            }
//        ]
//    }
//    """.trimIndent()
    val string = """
    {
        "component": {
            "group": "org.example",
            "name": "test"
        },
        "bundles": [
            {
                "platform": {
                    "architecture": "arm64",
                    "os": "linux"
                },
                "config": {
                },
                "layers": [
                    {
                    },
                    {
                        "component": {
                            "group": "org.example",
                            "name": "layer"
                        }
                    }
                ]
            }
        ]
    }
    """.trimIndent()
    val ociComponent = decode(string)
    println(encode(ociComponent).toString(2))
}