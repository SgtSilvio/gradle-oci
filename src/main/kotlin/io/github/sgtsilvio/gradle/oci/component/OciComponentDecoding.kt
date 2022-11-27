package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.internal.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

fun decodeComponent(string: String) = JSONObject(string).decodeComponent()

private fun JSONObject.decodeComponent() = OciComponent(
    key("capabilities") { arrayValue().decodeCapabilities() },
    optionalKey("prebuiltIndexDigest") { stringValue() },
    if (has("bundle")) {
        if (has("platformBundles")) throw JsonException("'bundle|platformBundles'", "must not both be present")
        key("bundle") { objectValue().decodeBundle() }
    } else {
        key("platformBundles") { arrayValue().decodePlatformBundles() }
    },
)

private fun JSONArray.decodeCapabilities() = toSet { objectValue().decodeCapability() }

private fun JSONObject.decodeCapability() = OciComponent.Capability(
    key("group") { stringValue() },
    key("name") { stringValue() },
)

private fun JSONArray.decodePlatformBundles() = OciComponent.PlatformBundles(toMap {
    objectValue().run {
        Pair(
            key("platform") { objectValue().decodePlatform() },
            key("bundle") { objectValue().decodeBundle() },
        )
    }
})

private fun JSONObject.decodePlatform() = OciComponent.Platform(
    key("architecture") { stringValue() },
    key("os") { stringValue() },
    optionalKey("osVersion") { stringValue() },
    optionalKey("osFeatures") { arrayValue().toList { stringValue() } } ?: listOf(),
    optionalKey("variant") { stringValue() },
)

private fun JSONObject.decodeBundle() = OciComponent.Bundle(
    optionalKey("prebuiltManifestDigest") { stringValue() },
    optionalKey("prebuiltConfigDigest") { stringValue() },
    optionalKey("creationTime") { stringValue() }?.let(Instant::parse),
    optionalKey("author") { stringValue() },
    optionalKey("user") { stringValue() },
    optionalKey("ports") { arrayValue().toSet { stringValue() } } ?: setOf(),
    optionalKey("environment") { objectValue().toMap { stringValue() } } ?: mapOf(),
    optionalKey("entryPoint") { arrayValue().toList { stringValue() } },
    optionalKey("arguments") { arrayValue().toList { stringValue() } },
    optionalKey("volumes") { arrayValue().toSet { stringValue() } } ?: setOf(),
    optionalKey("workingDirectory") { stringValue() },
    optionalKey("stopSignal") { stringValue() },
    optionalKey("annotations") { objectValue().toMap { stringValue() } } ?: mapOf(),
    optionalKey("parentCapabilities") { arrayValue().toList { arrayValue().decodeCapabilities() } } ?: listOf(),
    key("layers") { arrayValue().toList { objectValue().decodeLayer() } },
)

private fun JSONObject.decodeLayer() = OciComponent.Bundle.Layer(
    key("digest") { stringValue() },
    key("diffId") { stringValue() },
    optionalKey("creationTime") { stringValue() }?.let(Instant::parse),
    optionalKey("author") { stringValue() },
    optionalKey("createdBy") { stringValue() },
    optionalKey("comment") { stringValue() },
)

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
//                    "parentCapabilities": [
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