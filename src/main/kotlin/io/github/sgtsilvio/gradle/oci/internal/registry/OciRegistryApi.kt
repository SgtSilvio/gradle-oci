package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.MANIFEST_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.calculateOciDigest
import org.apache.commons.codec.binary.Hex
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.DigestException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.*

/**
 * @author Silvio Giebl
 */
class OciRegistryApi {

    private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val tokenCache: Cache<TokenCacheKey, String> =
        Caffeine.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).build()

    private data class TokenCacheKey(val registry: String, val imageName: String, val credentials: Credentials?)

    data class Credentials(val username: String, val password: String)

    data class Manifest(val mediaType: String, val data: String)

    fun pullManifest(
        registry: String,
        imageName: String,
        reference: String,
        credentials: Credentials?,
    ): Mono<Manifest> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            credentials,
            PULL_PERMISSION,
            HttpRequest.newBuilder().GET().setHeader(
                "accept",
                "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
            ),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> responseInfo.headers().firstValue("content-type").orElse(null)?.let { contentType ->
                    BodyHandlers.ofString().apply(responseInfo).map { Manifest(contentType, it) }
                } ?: createErrorBodySubscriber(responseInfo)

                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map { it.body() }
    }

    fun pullManifest(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
    ): Mono<Manifest> =
        pullManifest(registry, imageName, digest.toString(), credentials).map { manifest ->
            val manifestBytes = manifest.data.toByteArray()
            val actualDigest = manifestBytes.calculateOciDigest(digest.algorithm)
            when {
                digest != actualDigest -> throw digestMismatchException(digest.hash, actualDigest.hash)
                size != manifestBytes.size.toLong() -> throw sizeMismatchException(size, manifestBytes.size.toLong())
                else -> manifest
            }
        }

    fun <T> pullBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
        bodySubscriber: BodySubscriber<T>,
    ): Mono<T> {
        return send(
            registry,
            imageName,
            "blobs/$digest",
            credentials,
            PULL_PERMISSION,
            HttpRequest.newBuilder().GET(),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> bodySubscriber.verify(digest, size)
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map { it.body() }
    }

    fun pullBlobAsString(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
    ): Mono<String> =
        pullBlob(registry, imageName, digest, size, credentials, BodySubscribers.ofString(StandardCharsets.UTF_8))

    fun isBlobPresent(
        registry: String,
        imageName: String,
        digest: OciDigest,
        credentials: Credentials?,
    ): Mono<Boolean> {
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
        }.map { it.body() }
    }

    fun mountBlobOrCreatePushUrl(
        registry: String,
        imageName: String,
        digest: OciDigest?,
        sourceImageName: String?,
        credentials: Credentials?,
    ): Mono<URI?> {
        val isMount = digest != null
        val query =
            if (isMount) "?mount=$digest" + if (sourceImageName == null) "" else "&from=$sourceImageName" else ""
        return send(
            registry,
            imageName,
            "blobs/uploads/$query",
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder().POST(BodyPublishers.noBody()),
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                201 -> if (isMount) BodySubscribers.replacing(null) else createErrorBodySubscriber(responseInfo)
                202 -> responseInfo.headers().firstValue("location").orElse(null)?.let { location ->
                    BodySubscribers.replacing(URI(registry).resolve(location))
                } ?: createErrorBodySubscriber(responseInfo)

                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map { it.body() }
    }

//    fun cancelBlobPush(
//        registry: String,
//        imageName: String,
//        credentials: Credentials?,
//        uri: URI,
//    ): CompletableFuture<Unit> {
//        return send(
//            registry,
//            imageName,
//            credentials,
//            DELETE_PERMISSION,
//            HttpRequest.newBuilder(uri).DELETE(),
//        ) { responseInfo ->
//            when (responseInfo.statusCode()) {
//                204 -> BodySubscribers.discarding()
//                else -> createErrorBodySubscriber(responseInfo)
//            }
//        }.thenApply {}
//    }

    fun pushBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        credentials: Credentials?,
        uri: URI,
        bodyPublisher: BodyPublisher,
    ): Mono<Unit> {
        return send(
            registry,
            imageName,
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder(uri.addQueryParam("digest=$digest")).PUT(bodyPublisher)
                .setHeader("content-type", "application/octet-stream")
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                201 -> BodySubscribers.discarding()
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map {}
    }

    fun mountOrPushBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        sourceImageName: String?,
        credentials: Credentials?,
        bodyPublisher: BodyPublisher,
    ): Mono<Unit> = mountBlobOrCreatePushUrl(registry, imageName, digest, sourceImageName, credentials).flatMap { uri ->
        when (uri) {
            null -> Mono.just(Unit)
            else -> pushBlob(registry, imageName, digest, credentials, uri, bodyPublisher)
        }
    }

    fun pushBlobIfNotPresent(
        registry: String,
        imageName: String,
        digest: OciDigest,
        sourceImageName: String?,
        credentials: Credentials?,
        bodyPublisher: BodyPublisher,
    ): Mono<Unit> = isBlobPresent(registry, imageName, digest, credentials).flatMap { present ->
        when {
            present -> Mono.just(Unit)
            else -> mountOrPushBlob(registry, imageName, digest, sourceImageName, credentials, bodyPublisher)
        }
    }

    fun pushManifest(
        registry: String,
        imageName: String,
        reference: String,
        credentials: Credentials?,
        manifest: Manifest,
    ): Mono<Unit> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            credentials,
            PUSH_PERMISSION,
            HttpRequest.newBuilder().PUT(BodyPublishers.ofString(manifest.data))
                .setHeader("content-type", manifest.mediaType)
        ) { responseInfo ->
            when (responseInfo.statusCode()) {
                201 -> BodySubscribers.discarding()
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map {}
    }

    fun pushManifest(
        registry: String,
        imageName: String,
        digest: OciDigest,
        credentials: Credentials?,
        manifest: Manifest,
    ): Mono<Unit> = pushManifest(registry, imageName, digest.toString(), credentials, manifest)

//    fun deleteBlob(
//        registry: String,
//        imageName: String,
//        digest: OciDigest,
//        credentials: Credentials?,
//    ): CompletableFuture<Unit> {
//        return send(
//            registry,
//            imageName,
//            "blobs/$digest",
//            credentials,
//            PUSH_PERMISSION,
//            HttpRequest.newBuilder().DELETE(),
//        ) { responseInfo ->
//            when (responseInfo.statusCode()) {
//                202 -> BodySubscribers.discarding()
//                else -> createErrorBodySubscriber(responseInfo)
//            }
//        }.thenApply {}
//    }
//
//    fun deleteManifest(
//        registry: String,
//        imageName: String,
//        reference: String,
//        credentials: Credentials?,
//    ): CompletableFuture<Unit> {
//        return send(
//            registry,
//            imageName,
//            "manifests/$reference",
//            credentials,
//            PUSH_PERMISSION,
//            HttpRequest.newBuilder().DELETE(),
//        ) { responseInfo ->
//            when (responseInfo.statusCode()) {
//                202 -> BodySubscribers.discarding()
//                else -> createErrorBodySubscriber(responseInfo)
//            }
//        }.thenApply {}
//    }

    private fun <T> send(
        registry: String,
        imageName: String,
        path: String,
        credentials: Credentials?,
        permission: String,
        requestBuilder: HttpRequest.Builder,
        responseBodyHandler: BodyHandler<T>,
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
        responseBodyHandler: BodyHandler<T>,
    ): Mono<HttpResponse<T>> {
//        println("SEND: " + requestBuilder.build().method() + " " + requestBuilder.build().uri())
        getAuthorization(registry, imageName, credentials)?.let { requestBuilder.setHeader("authorization", it) }
        val bodyHandler = BodyHandler { responseInfo -> CancellableBodySubscriber(responseBodyHandler.apply(responseInfo)) }
        return httpClient.sendMono(requestBuilder.build(), bodyHandler).onErrorResume { error ->
//            println(error)
            when {
                error !is HttpResponseException -> Mono.error(error)
                error.statusCode != 401 -> Mono.error(error)
                else -> tryAuthorize(error, registry, imageName, credentials, permission)?.flatMap { authorization ->
                    httpClient.sendMono(requestBuilder.setHeader("authorization", authorization).build(), bodyHandler)
                } ?: Mono.error(error)
            }
        }
//        }.thenApply { response ->
//            var r: HttpResponse<T>? = response
//            while (r != null) {
//                println("${r.uri()} ${r.statusCode()}\n > " + r.headers().map().entries.joinToString("\n > "))
//                r = r.previousResponse().orElse(null)
//            }
//            response
//        }
    }

    class CancellableBodySubscriber<T>(private val delegate: BodySubscriber<T>) : BodySubscriber<T> by delegate {
        private var subscription: Flow.Subscription? = null

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            delegate.onSubscribe(subscription)
        }

        override fun getBody(): CompletionStage<T> = delegate.body.toCancelable {
            println("YEAH")
            subscription?.cancel()
            true
        }
    }

    private fun tryAuthorize(
        responseException: HttpResponseException,
        registry: String,
        imageName: String,
        credentials: Credentials?,
        permission: String,
    ): Mono<String>? {
        val bearerParams = decodeBearerParams(responseException.headers) ?: return null
        val realm = bearerParams["realm"] ?: return null
        val service = bearerParams["service"] ?: registry
        val scope = bearerParams["scope"] ?: "repository:$imageName:$permission"
        val scopeParams = "scope=" + scope.replace(" ", "&scope=")
        val requestBuilder = HttpRequest.newBuilder(URI("$realm?service=$service&$scopeParams")).GET()
        if (credentials != null) {
            requestBuilder.setHeader("authorization", encodeBasicAuthorization(credentials))
        }
//        println("AUTH: " + requestBuilder.build().method() + " " + requestBuilder.build().uri())
        return httpClient.sendMono(requestBuilder.build()) { responseInfo ->
            when (responseInfo.statusCode()) {
                200 -> BodyHandlers.ofString().apply(responseInfo)
                else -> createErrorBodySubscriber(responseInfo)
            }
        }.map { response ->
            val authorization = "Bearer " + jsonObject(response.body()).run {
                if (hasKey("token")) getString("token") else getString("access_token")
            }
            tokenCache.put(TokenCacheKey(registry, imageName, credentials), authorization)
            authorization
        }
    }

    private fun getAuthorization(registry: String, imageName: String, credentials: Credentials?): String? =
        tokenCache.getIfPresent(TokenCacheKey(registry, imageName, credentials))
            ?: credentials?.let(::encodeBasicAuthorization)

    private fun encodeBasicAuthorization(credentials: Credentials) =
        "Basic " + Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray())

    private fun decodeBearerParams(headers: Map<String, List<String>>): Map<String, String>? {
        val authHeader = headers["www-authenticate"]?.firstOrNull() ?: return null
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

    private fun <T> createErrorBodySubscriber(responseInfo: ResponseInfo) =
        BodyHandlers.ofString().apply(responseInfo).map<String, T> { body ->
            throw HttpResponseException(responseInfo.statusCode(), responseInfo.headers().map(), body)
        }

    private fun <T, R> BodySubscriber<T>.map(mapper: (T) -> R): BodySubscriber<R> =
        BodySubscribers.mapping(this, mapper)
}

fun <T> HttpClient.sendMono(
    request: HttpRequest,
    responseBodyHandler: BodyHandler<T>,
): Mono<HttpResponse<T>> = Mono.create { monoSink ->
    sendAsync(request) { responseInfo ->
        val bodySubscriber = responseBodyHandler.apply(responseInfo)
        object : BodySubscriber<T> by bodySubscriber {
            override fun onSubscribe(subscription: Flow.Subscription) {
                monoSink.onCancel { subscription.cancel() }
                bodySubscriber.onSubscribe(subscription)
            }
        }
    }.whenComplete { response, error ->
        if (error == null) {
            monoSink.success(response)
        } else {
            var unpackedError = error
            while (unpackedError is CompletionException) {
                unpackedError = unpackedError.cause
            }
            monoSink.error(unpackedError)
        }
    }
}

class DigestBodySubscriber<T>(
    private val bodySubscriber: BodySubscriber<T>,
    private val messageDigest: MessageDigest,
    private val expectedDigest: ByteArray,
    private val expectedSize: Long,
) : BodySubscriber<T>, Flow.Subscription {
    private lateinit var subscription: Flow.Subscription
    private var actualSize = 0L
    private var done = false

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        bodySubscriber.onSubscribe(this)
    }

    override fun onNext(item: MutableList<ByteBuffer>) {
        if (done) return
        for (byteBuffer in item) {
            messageDigest.update(byteBuffer.duplicate())
            actualSize += byteBuffer.remaining()
        }
        if (actualSize >= expectedSize) {
            if (actualSize > expectedSize) {
                subscription.cancel()
                done = true
                bodySubscriber.onError(sizeMismatchException(expectedSize, actualSize))
                return
            }
            val actualDigest = messageDigest.digest()
            if (!expectedDigest.contentEquals(actualDigest)) {
                subscription.cancel()
                done = true
                bodySubscriber.onError(digestMismatchException(expectedDigest, actualDigest))
                return
            }
        }
        bodySubscriber.onNext(item)
    }

    override fun onError(throwable: Throwable?) {
        if (done) return else done = true
        bodySubscriber.onError(throwable)
    }

    override fun onComplete() {
        if (done) return else done = true
        if (actualSize < expectedSize) {
            bodySubscriber.onError(sizeMismatchException(expectedSize, actualSize))
        } else {
            bodySubscriber.onComplete()
        }
    }

    override fun request(n: Long) = subscription.request(n)

    override fun cancel() = subscription.cancel()

    override fun getBody(): CompletionStage<T> = bodySubscriber.body
}

private fun digestMismatchException(expectedDigest: ByteArray, actualDigest: ByteArray): DigestException {
    val expectedHex = Hex.encodeHexString(expectedDigest)
    val actualHex = Hex.encodeHexString(actualDigest)
    return DigestException("expected and actual digests do not match (expected: $expectedHex, actual: $actualHex)")
}

private fun sizeMismatchException(expectedSize: Long, actualSize: Long) =
    DigestException("expected and actual size do not match (expected: $expectedSize, actual: $actualSize)")

fun <T> BodySubscriber<T>.verify(digest: OciDigest, size: Long) =
    DigestBodySubscriber(this, digest.algorithm.createMessageDigest(), digest.hash, size)

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

fun URI.addQueryParam(param: String) = URI(toString() + (if (query == null) "?" else "&") + param)

const val DOCKER_MANIFEST_LIST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.list.v2+json"
const val DOCKER_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"
const val DOCKER_CONFIG_MEDIA_TYPE = "application/vnd.docker.container.image.v1+json"
const val DOCKER_LAYER_MEDIA_TYPE = "application/vnd.docker.image.rootfs.diff.tar.gzip"
private const val PULL_PERMISSION = "pull"
private const val PUSH_PERMISSION = "pull,push"
