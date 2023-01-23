package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import io.github.sgtsilvio.gradle.oci.internal.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.*

fun decodeComponent(string: String) = JSONObject(string).decodeComponent()

private fun JSONObject.decodeComponent() = OciComponent(
    key("capabilities") { arrayValue().toSet(TreeSet()) { objectValue().decodeCapability() } },
    if (has("bundle")) {
        if (has("platformBundles")) throw JsonException("bundle|platformBundles", "must not both be present")
        key("bundle") { objectValue().decodeBundle() }
    } else {
        key("platformBundles") { arrayValue().decodePlatformBundles() }
    },
    optionalKey("indexAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
)

private fun JSONObject.decodeCapability() = OciComponent.Capability(
    key("group") { stringValue() },
    key("name") { stringValue() },
)

private fun JSONArray.decodePlatformBundles() = OciComponent.PlatformBundles(toMap(TreeMap()) {
    objectValue().run {
        Pair(
            key("platform") { objectValue().decodePlatform() },
            key("bundle") { objectValue().decodeBundle() },
        )
    }
})

private fun JSONObject.decodePlatform() = PlatformImpl(
    key("os") { stringValue() },
    key("architecture") { stringValue() },
    optionalKey("variant") { stringValue() } ?: "",
    optionalKey("osVersion") { stringValue() } ?: "",
    optionalKey("osFeatures") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
)

private fun JSONObject.decodeBundle() = OciComponent.Bundle(
    optionalKey("creationTime") { stringValue() }?.let(Instant::parse),
    optionalKey("author") { stringValue() },
    optionalKey("user") { stringValue() },
    optionalKey("ports") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
    optionalKey("environment") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("command") { objectValue().decodeCommand() },
    optionalKey("volumes") { arrayValue().toSet(TreeSet()) { stringValue() } } ?: sortedSetOf(),
    optionalKey("workingDirectory") { stringValue() },
    optionalKey("stopSignal") { stringValue() },
    optionalKey("configAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("configDescriptorAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("manifestAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("manifestDescriptorAnnotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
    optionalKey("parentCapabilities") { arrayValue().toList { objectValue().decodeCapability() } } ?: listOf(),
    key("layers") { arrayValue().toList { objectValue().decodeLayer() } },
)

private fun JSONObject.decodeCommand() = OciComponent.Bundle.Command(
    optionalKey("entryPoint") { arrayValue().toList { stringValue() } },
    key("arguments") { arrayValue().toList { stringValue() } },
)

private fun JSONObject.decodeLayer() = OciComponent.Bundle.Layer(
    if (has("digest") || has("diffId") || has("size") || has("annotations")) {
        OciComponent.Bundle.Layer.Descriptor(
            key("digest") { stringValue() },
            key("diffId") { stringValue() },
            key("size") { longValue() },
            optionalKey("annotations") { objectValue().toMap(TreeMap()) { stringValue() } } ?: sortedMapOf(),
        )
    } else null,
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
//        "platformBundles": [
//            {
//                "platform": {
//                    "architecture": "arm64",
//                    "os": "linux",
//                    "variant": "v8"
//                },
//                "bundle": {
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
//                    "command": {
//                        "entryPoint": ["bin/sh", "-c"],
//                        "arguments": ["echo", "hello"]
//                    },
//                    "volumes": [
//                        "/data",
//                        "/log"
//                    ],
//                    "workingDirectory": "/home/example",
//                    "stopSignal": "SIGKILL",
//                    "configAnnotations": {
//                        "hey": "ho",
//                        "aso": "wabern"
//                    },
//                    "configDescriptorAnnotations": {
//                        "hey": "ho",
//                        "aso": "wabern"
//                    },
//                    "manifestAnnotations": {
//                        "hey": "ho",
//                        "aso": "wabern"
//                    },
//                    "manifestDescriptorAnnotations": {
//                        "hey": "ho",
//                        "aso": "wabern"
//                    },
//                    "parentCapabilities": [
//                        {
//                            "group": "org.example",
//                            "name": "base"
//                        },
//                        {
//                            "group": "org.example",
//                            "name": "base2"
//                        }
//                    ],
//                    "layers": [
//                        {
//                            "digest": "sha256:456",
//                            "diffId": "sha256:567",
//                            "size": 456,
//                            "creationTime": "${Instant.EPOCH}",
//                            "author": "John Doe",
//                            "createdBy": "hand",
//                            "comment": "ja",
//                            "annotations": {
//                                "hey": "ho",
//                                "aso": "wabern"
//                            }
//                        },
//                        {
//                            "digest": "sha256:678",
//                            "diffId": "sha256:789",
//                            "size": 678
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
//                            "diffId": "sha256:567",
//                            "size": 456
//                        }
//                    ]
//                }
//            }
//        ],
//        "indexAnnotations": {
//            "hey": "ho",
//            "aso": "wabern"
//        }
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
                    "diffId": "sha256:567",
                    "size": 456
                }
            ]
        }
    }
    """.trimIndent()
    val ociComponent = decodeComponent(string)
    println(encodeComponent(ociComponent))
    println(JSONObject(encodeComponent(ociComponent)).toString(2))
}