package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.toOciDigest
import io.netty.handler.codec.http.HttpMethod.*
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * @author Silvio Giebl
 */
class OciRegistry(private val directory: Path) {

    fun start(port: Int) =
        Started(HttpServer.create().bindAddress { InetSocketAddress("localhost", port) }.handle(::handle).bindNow())

    class Started(private val server: DisposableServer) {
        val port get() = server.port()

        fun stop() {
            server.disposeNow()
        }
    }


    private fun handle(request: HttpServerRequest, response: HttpServerResponse): Publisher<Void> {
        val segments = request.uri().substring(1).split('/')
        return when {
            segments[0] == "v2" -> handleV2(request, segments.drop(1), response)
            else -> response.sendNotFound()
        }
    }

    private fun handleV2(
        request: HttpServerRequest,
        segments: List<String>,
        response: HttpServerResponse,
    ): Publisher<Void> = when {
        segments.isEmpty() || segments[0].isEmpty() -> when (request.method()) {
            GET, HEAD -> response.header("Docker-Distribution-API-Version", "registry/2.0").send()
            else -> response.status(405).send()
        }

        (segments.size == 1) && (segments[0] == "_catalog") -> when (request.method()) {
            GET -> response.status(403).send()
            else -> response.status(405).send()
        }

        segments.size < 3 -> response.sendNotFound()

        else -> when (segments[segments.lastIndex - 1]) {
            "tags" -> if (segments[segments.lastIndex] == "list") {
                when (request.method()) {
                    GET -> response.status(403).send()
                    else -> response.status(405).send()
                }
            } else response.sendNotFound()

            "manifests" -> when (request.method()) {
                GET -> getOrHeadManifest(segments, true, response)
                HEAD -> getOrHeadManifest(segments, false, response)
                PUT, DELETE -> response.status(403).send()
                else -> response.status(405).send()
            }

            "blobs" -> when (request.method()) {
                GET -> getOrHeadBlob(segments, true, response)
                HEAD -> getOrHeadBlob(segments, false, response)
                DELETE -> response.status(403).send()
                else -> response.status(405).send()
            }

            "uploads" -> when (request.method()) {
                POST, GET, PATCH, PUT, DELETE -> response.status(403).send()
                else -> response.status(405).send()
            }

            else -> response.sendNotFound()
        }
    }

    private fun getOrHeadManifest(
        segments: List<String>,
        isGET: Boolean,
        response: HttpServerResponse,
    ): Publisher<Void> {
        val name = decodeName(segments, segments.lastIndex - 1)
        val reference = segments[segments.lastIndex]
        val manifestsDirectory = resolveRepositoryDirectory(name).resolve("_manifests")
        val digest = if (':' in reference) {
            val digest = try {
                reference.toOciDigest()
            } catch (e: IllegalArgumentException) {
                return response.status(400).send()
            }
            if (!Files.exists(manifestsDirectory.resolve("revisions").resolveLinkFile(digest))) {
                return response.sendNotFound()
            }
            digest
        } else {
            val linkFile = manifestsDirectory.resolve("tags").resolve(reference).resolve("current/link")
            if (!Files.exists(linkFile)) {
                return response.sendNotFound()
            }
            Files.readString(linkFile).toOciDigest()
        }
        val dataFile = resolveBlobFile(digest)
        if (!Files.exists(dataFile)) {
            return response.sendNotFound()
        }
        val data = Files.readAllBytes(dataFile)
        response.header("Content-Type", jsonObject(data.decodeToString()).getString("mediaType"))
        response.header("Content-Length", data.size.toString())
        return if (isGET) response.sendByteArray(Mono.just(data)) else response.send()
    }

    private fun getOrHeadBlob(segments: List<String>, isGET: Boolean, response: HttpServerResponse): Publisher<Void> {
        val name = decodeName(segments, segments.lastIndex - 1)
        val digest = try {
            segments[segments.lastIndex].toOciDigest()
        } catch (e: IllegalArgumentException) {
            return response.status(400).send()
        }
        if (!Files.exists(resolveRepositoryDirectory(name).resolve("_layers").resolveLinkFile(digest))) {
            return response.sendNotFound()
        }
        val dataFile = resolveBlobFile(digest)
        if (!Files.exists(dataFile)) {
            return response.sendNotFound()
        }
        response.header("Content-Type", "application/octet-stream")
        response.header("Content-Length", Files.size(dataFile).toString())
        return if (isGET) response.sendFile(dataFile) else response.send()
    }

    private fun decodeName(segments: List<String>, toIndex: Int) = segments.subList(0, toIndex).joinToString("/")

    private fun resolveRepositoryDirectory(name: String) = directory.resolve("repositories").resolve(name)

    private fun Path.resolveLinkFile(digest: OciDigest) =
        resolve(digest.algorithm.ociPrefix).resolve(digest.encodedHash).resolve("link")

    private fun resolveBlobFile(digest: OciDigest) =
        directory.resolve("blobs").resolve(digest.algorithm.ociPrefix).resolve(digest.encodedHash.substring(0, 2))
            .resolve(digest.encodedHash).resolve("data")
}

fun main() {
    val registry = OciRegistry(Path.of("example/build/oci/registry/test"))
    val started = registry.start(0)
    println(started.port)
    Thread.sleep(1000000)
}