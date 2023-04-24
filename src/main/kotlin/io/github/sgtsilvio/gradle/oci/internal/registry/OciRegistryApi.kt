package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.MANIFEST_MEDIA_TYPE
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpResponse.BodySubscribers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.*

/**
 * @author Silvio Giebl
 */
class RegistryApi {

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val authorizationCache = ConcurrentHashMap<TokenCacheKey, String>()

    private data class TokenCacheKey(val registry: String, val credentials: Credentials?)

    data class Credentials(val username: String, val password: String)

    data class Manifest(val mediaType: String, val data: String)

    fun pullManifest(
        registry: String,
        imageName: String,
        reference: String,
        credentials: Credentials?,
    ): CompletableFuture<Manifest> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            credentials,
            PULL_PERMISSION,
            HttpRequest.newBuilder().GET().setHeader(
                "Accept",
                "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
            ),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> responseInfo.headers().firstValue("Content-Type").orElse(null)?.let { contentType ->
                    BodyHandlers.ofString().apply(responseInfo).map { Manifest(contentType, it) }
                } ?: createErrorBodySubscriber(responseInfo)

                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { it.body() }
    }

    fun pullBlobAsString(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
    ): CompletableFuture<String> =
        pullBlob(registry, imageName, digest, credentials, BodySubscribers.ofString(StandardCharsets.UTF_8))

    fun pullBlob(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
    ): CompletableFuture<Path> = pullBlob(
        registry,
        imageName,
        digest,
        credentials,
        BodySubscribers.ofFile(Files.createTempFile(null, null), StandardOpenOption.WRITE), // TODO dest file
    )

    private fun <T> pullBlob(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
        bodySubscriber: BodySubscriber<T>,
    ): CompletableFuture<T> {
        return send(
            registry,
            imageName,
            "blobs/$digest",
            credentials,
            PULL_PERMISSION,
            HttpRequest.newBuilder().GET(),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> bodySubscriber
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { it.body() }
    }

    fun isBlobPresent(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
    ): CompletableFuture<Boolean> {
        return send(
            registry,
            imageName,
            "blobs/$digest",
            credentials,
            PULL_PERMISSION,
            HttpRequest.newBuilder().method("HEAD", BodyPublishers.noBody()),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> BodySubscribers.replacing(true)
                404 -> BodySubscribers.replacing(false)
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { it.body() }
    }

    fun startBlobPush(registry: String, imageName: String, credentials: Credentials?): CompletableFuture<URI> {
        return send(
            registry,
            imageName,
            "blobs/uploads",
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder().POST(BodyPublishers.noBody()),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                202 -> responseInfo.headers().firstValue("Location").orElse(null)?.let { location ->
                    BodySubscribers.replacing(URI(location))
                } ?: createErrorBodySubscriber(responseInfo)

                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { it.body() }
    }

    fun pushBlob(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
        uri: URI,
        blob: Path,
    ): CompletableFuture<Void> {
        return send(
            registry,
            imageName,
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder(URI("$uri?digest=$digest")).PUT(BodyPublishers.ofFile(blob))
                .setHeader("Content-Type", "application/octet-stream")
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                201 -> BodySubscribers.discarding()
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { null }
    }

    fun pushBlobIfNotPresent(
        registry: String,
        imageName: String,
        digest: String,
        credentials: Credentials?,
        blob: Path,
    ): CompletableFuture<Void> {
        return isBlobPresent(registry, imageName, digest, credentials).thenCompose { present ->
            if (present) {
                CompletableFuture.completedFuture(null)
            } else {
                startBlobPush(registry, imageName, credentials).thenCompose { uri ->
                    pushBlob(registry, imageName, digest, credentials, uri, blob)
                }
            }
        }
    }

    fun pushManifest(
        registry: String,
        imageName: String,
        reference: String,
        credentials: Credentials?,
        manifest: Manifest,
    ): CompletableFuture<Void> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder().PUT(BodyPublishers.ofString(manifest.data))
                .setHeader("Content-Type", manifest.mediaType)
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                201 -> BodySubscribers.discarding()
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { null }
    }

    private fun <T> send(
        registry: String,
        imageName: String,
        path: String,
        credentials: Credentials?,
        permission: String,
        requestBuilder: HttpRequest.Builder,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ) = send(
        registry,
        imageName,
        credentials,
        permission,
        requestBuilder.uri(URI("$registry/v2/$imageName/$path")),
        responseBodyHandler
    )

    private fun <T> send(
        registry: String,
        imageName: String,
        credentials: Credentials?,
        permission: String,
        requestBuilder: HttpRequest.Builder,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): CompletableFuture<HttpResponse<T>> {
        getAuthorization(registry, credentials)?.let { requestBuilder.setHeader("Authorization", it) }
        return httpClient.sendAsync(requestBuilder.build(), responseBodyHandler).flatMapError { error ->
            if (error !is HttpResponseException) throw error
            if (error.statusCode != 401) throw error
            tryAuthorize(error, registry, imageName, credentials, permission)?.thenCompose { authorization ->
                httpClient.sendAsync(
                    requestBuilder.setHeader("Authorization", authorization).build(),
                    responseBodyHandler,
                )
            } ?: throw error
        }
    }

    private fun tryAuthorize(
        responseException: HttpResponseException,
        registry: String,
        imageName: String,
        credentials: Credentials?,
        permission: String
    ): CompletableFuture<String>? {
        val bearerParams = decodeBearerParams(responseException.headers) ?: return null
        val realm = bearerParams["realm"] ?: return null
        val service = bearerParams["service"] ?: registry
        val scope = bearerParams["scope"] ?: "repository:$imageName:$permission"
        val requestBuilder = HttpRequest.newBuilder(URI("$realm?service=$service&scope=$scope")).GET()
        if (credentials != null) {
            requestBuilder.setHeader("Authorization", encodeBasicAuthorization(credentials))
        }
        return httpClient.sendAsync(requestBuilder.build()) { responseInfo ->
            if (responseInfo.statusCode() == 200) {
                BodyHandlers.ofString().apply(responseInfo)
            } else {
                createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { response ->
            val authorization = "Bearer " + jsonObject(response.body()).run {
                if (hasKey("token")) getString("token") else getString("access_token")
            }
            authorizationCache[TokenCacheKey(registry, credentials)] = authorization
            authorization
        }
    }

    private fun getAuthorization(registry: String, credentials: Credentials?) =
        authorizationCache[TokenCacheKey(registry, credentials)] ?: credentials?.let(::encodeBasicAuthorization)

    private fun encodeBasicAuthorization(credentials: Credentials) =
        "Basic " + Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray())

    private fun decodeBearerParams(headers: Map<String, List<String>>): Map<String, String>? {
        val authHeader = headers["WWW-Authenticate"]?.firstOrNull() ?: return null
        if (!authHeader.startsWith("Bearer ")) return null
        val authParamString = authHeader.substring("Bearer ".length)
        val map = HashMap<String, String>()
        var i = 0
        while (true) {
            val keyEndIndex = authParamString.indexOf('=', i)
            if (keyEndIndex == -1) break
            val key = authParamString.substring(i, keyEndIndex).trim()
            val valueStartIndex = authParamString.indexOf('"', keyEndIndex + 1)
            if (valueStartIndex == -1) break
            val valueEndIndex = authParamString.indexOf('"', valueStartIndex + 1)
            if (valueEndIndex == -1) break
            val value = authParamString.substring(valueStartIndex + 1, valueEndIndex).trim()
            map[key] = value
            i = authParamString.indexOf(',', valueEndIndex + 1)
            if (i == -1) break
            i++
        }
        return map
    }

    private fun <T> createErrorBodySubscriber(responseInfo: HttpResponse.ResponseInfo) =
        BodyHandlers.ofString().apply(responseInfo).map<String, T> { body ->
            throw HttpResponseException(responseInfo.statusCode(), responseInfo.headers().map(), body)
        }

    private fun <T, R> HttpResponse.BodySubscriber<T>.map(mapper: (T) -> R): HttpResponse.BodySubscriber<R> =
        BodySubscribers.mapping(this, mapper)

    private fun <T> CompletableFuture<T>.flatMapError(mapper: (Throwable) -> CompletionStage<T>): CompletableFuture<T> =
        handle { t, error ->
            if (t != null) return@handle CompletableFuture.completedFuture(t)
            var unpackedError = error
            while (unpackedError is CompletionException) {
                unpackedError = unpackedError.cause
            }
            mapper.invoke(unpackedError)
        }.thenCompose { it }
}

class HttpResponseException(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) : RuntimeException("unexpected http response", null, false, false) {
    override val message get() = super.message + ": " + responseToString()

    private fun responseToString() = buildString {
        append(statusCode).append('\n')
        for ((key, value) in headers) {
            append(key).append(": ").append(value).append('\n')
        }
        append(body)
    }
}

private const val DOCKER_MANIFEST_LIST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.list.v2+json"
private const val DOCKER_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"
private const val PULL_PERMISSION = "pull"
private const val PUSH_PERMISSION = "pull,push"

fun main() {
    val registryApi = RegistryApi()
    for (i in 0..0) {
        val t3 = System.nanoTime()
        println(
            registryApi.pullManifest(
                "https://registry-1.docker.io",
                "library/registry",
                "2",
                null,
            ).get()
        )
        println(
            registryApi.pullManifest(
                "https://registry-1.docker.io",
                "library/registry",
                "sha256:7c8b70990dad7e4325bf26142f59f77c969c51e079918f4631767ac8d49e22fb",
                null,
            ).get()
        )
        println(
            registryApi.pullBlobAsString(
                "https://registry-1.docker.io",
                "library/registry",
                "sha256:8db46f9d755043e6c427912d5c36b4375d68d31ab46ef9782fef06bdee1ed2cd",
                null,
            ).get()
        )
        println(
            registryApi.pullBlob(
                "https://registry-1.docker.io",
                "library/registry",
                "sha256:91d30c5bc19582de1415b18f1ec5bcbf52a558b62cf6cc201c9669df9f748c22",
                null,
            ).get()
        )
        println(
            registryApi.isBlobPresent(
                "https://registry-1.docker.io",
                "library/registry",
                "sha256:7c8b70990dad7e4325bf26142f59f77c969c51e079918f4631767ac8d49e22fb",
                null,
            ).get()
        )
        println(
            registryApi.isBlobPresent(
                "https://registry-1.docker.io",
                "library/registry",
                "sha256:8db46f9d755043e6c427912d5c36b4375d68d31ab46ef9782fef06bdee1ed2cd",
                null,
            ).get()
        )
        val t4 = System.nanoTime()
        println(TimeUnit.NANOSECONDS.toMillis(t4 - t3))
    }
}
