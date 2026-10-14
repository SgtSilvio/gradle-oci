package io.github.sgtsilvio.gradle.oci

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * @author David Sondermann
 */
internal class PushTest {

    @Test
    fun blobSharedByPlatformsIsMountedOncePerRepository(@TempDir projectDir: File) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.sgtsilvio.gradle.oci")
            }
            group = "org.example"
            version = "1.0.0"
            for (name in listOf("a", "b")) {
                oci.imageDefinitions.register(name) {
                    imageName = name
                    allPlatforms {
                        layer("shared") {
                            contents {
                                from("shared.txt")
                            }
                        }
                    }
                    specificPlatform(oci.platform("linux", "amd64"))
                    specificPlatform(oci.platform("linux", "arm64", "v8"))
                }
            }
            tasks.register("pushAll", oci.pushImagesTaskClass) {
                from(oci.imageDependencies.create("pushAll") {
                    runtime(oci.imageDefinitions["a"].dependency)
                    runtime(oci.imageDefinitions["b"].dependency)
                })
            }
            """.trimIndent()
        )
        projectDir.resolve("shared.txt").writeText("shared")

        FakeRegistry().use { registry ->
            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("pushAll", "--url", registry.url)
                .build()
            assertEquals(TaskOutcome.SUCCESS, result.task(":pushAll")?.outcome)

            val uploadRequests = registry.blobUploadRequests
            assertTrue(uploadRequests.any { (it.from != null) && (it.from != it.repository) })
            for ((key, requests) in uploadRequests.groupBy { Pair(it.repository, it.digest) }) {
                assertEquals(1, requests.size, "duplicate blob uploads for $key: $requests")
            }
        }
    }

    private class FakeRegistry : AutoCloseable {
        val blobUploadRequests: MutableList<BlobUploadRequest> = CopyOnWriteArrayList()
        private val blobs: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val url get() = "http://localhost:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/v2/") { exchange -> exchange.use { handle(it) } }
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            exchange.requestBody.readAllBytes()
            val path = exchange.requestURI.path
            val query = parseQuery(exchange.requestURI.query)
            val blobMatch = BLOB_PATH.matchEntire(path)
            val uploadMatch = UPLOAD_PATH.matchEntire(path)
            val manifestMatch = MANIFEST_PATH.matchEntire(path)
            when (exchange.requestMethod) {
                // Never confirming a blob makes every blob push of the plugin end in an upload request.
                "HEAD" if blobMatch != null -> exchange.sendResponseHeaders(404, -1)

                "POST" if (uploadMatch != null) && (uploadMatch.groupValues[2] == "") -> {
                    val repository = uploadMatch.groupValues[1]
                    val digest = query["mount"]
                    val from = query["from"]
                    blobUploadRequests += BlobUploadRequest(repository, digest, from)
                    if ((digest != null) && (from != null) && (Pair(from, digest) in blobs)) {
                        blobs += Pair(repository, digest)
                        exchange.sendResponseHeaders(201, -1)
                    } else {
                        exchange.responseHeaders["Location"] = "/v2/$repository/blobs/uploads/${UUID.randomUUID()}"
                        exchange.sendResponseHeaders(202, -1)
                    }
                }

                "PUT" if (uploadMatch != null) && (query["digest"] != null) -> {
                    blobs += Pair(uploadMatch.groupValues[1], query["digest"]!!)
                    exchange.sendResponseHeaders(201, -1)
                }

                "PUT" if manifestMatch != null -> exchange.sendResponseHeaders(201, -1)
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class BlobUploadRequest(val repository: String, val digest: String?, val from: String?)
}

private val BLOB_PATH = Regex("/v2/(.+)/blobs/(sha256:[0-9a-f]+)")
private val UPLOAD_PATH = Regex("/v2/(.+)/blobs/uploads/(.*)")
private val MANIFEST_PATH = Regex("/v2/(.+)/manifests/(.+)")

private fun parseQuery(query: String?): Map<String, String> =
    query?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') } ?: emptyMap()
