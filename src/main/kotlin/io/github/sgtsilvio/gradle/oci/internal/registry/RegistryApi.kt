package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.MANIFEST_MEDIA_TYPE
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpResponse.BodySubscribers
import java.util.*
import java.util.concurrent.*

/**
 * @author Silvio Giebl
 */
class RegistryApi {

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val authorizationCache = ConcurrentHashMap<TokenCacheKey, String>()

    private data class TokenCacheKey(val registry: String, val credentials: Credentials?, val operation: String)

    data class Credentials(val username: String, val password: String)

    fun pullManifest(
        registry: String,
        imageName: String,
        reference: String,
        credentials: Credentials?,
    ): CompletableFuture<String> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            credentials,
            "pull",
            HttpRequest.newBuilder().GET().header(
                "Accept",
                "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
            ),
        ) { responseInfo ->
            if (responseInfo.statusCode() == 200) {
                BodyHandlers.ofString().apply(responseInfo)
            } else {
                createErrorBodySubscriber(responseInfo)
            }
        }.thenApply { it.body() }
    }

    private fun <T> send(
        registry: String,
        imageName: String,
        path: String,
        credentials: Credentials?,
        operation: String,
        requestBuilder: HttpRequest.Builder,
        responseBodyHandler: HttpResponse.BodyHandler<T>
    ): CompletableFuture<HttpResponse<T>> {
        requestBuilder.uri(URI("$registry/v2/$imageName/$path"))
        getAuthorization(registry, credentials, operation)?.let { requestBuilder.authorization(it) }
        return httpClient.sendAsync(requestBuilder.build(), responseBodyHandler).flatMapError { error ->
            if (error !is HttpResponseException) throw error
            if (error.statusCode != 401) throw error
            tryAuthorize(error, registry, imageName, credentials, operation)?.thenCompose { authorization ->
                httpClient.sendAsync(requestBuilder.authorization(authorization).build(), responseBodyHandler)
            } ?: throw error
        }
    }

    private fun tryAuthorize(
        responseException: HttpResponseException,
        registry: String,
        imageName: String,
        credentials: Credentials?,
        operation: String
    ): CompletableFuture<String>? {
        val bearerParams = decodeBearerParams(responseException.headers) ?: return null
        val realm = bearerParams["realm"] ?: return null
        val service = bearerParams["service"] ?: registry
        val scope = bearerParams["scope"] ?: "repository:$imageName:$operation"
        val requestBuilder = HttpRequest.newBuilder(URI("$realm?service=$service&scope=$scope")).GET()
        if (credentials != null) {
            requestBuilder.authorization(encodeBasicAuthorization(credentials))
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
            authorizationCache[TokenCacheKey(registry, credentials, operation)] = authorization
            authorization
        }
    }

    private fun getAuthorization(registry: String, credentials: Credentials?, operation: String) =
        authorizationCache[TokenCacheKey(registry, credentials, operation)]
            ?: credentials?.let(::encodeBasicAuthorization)

    private fun HttpRequest.Builder.authorization(value: String): HttpRequest.Builder =
        setHeader("Authorization", value)

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

const val DOCKER_MANIFEST_LIST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.list.v2+json"
const val DOCKER_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"

fun main() {
    val registryApi = RegistryApi()
    for (i in 0..1) {
        val t3 = System.nanoTime()
        println(
            registryApi.pullManifest(
                "https://registry-1.docker.io",
                "library/registry",
                "2",
                null,
            ).get()
        )
        val t4 = System.nanoTime()
        println(TimeUnit.NANOSECONDS.toMillis(t4 - t3))
    }
}
