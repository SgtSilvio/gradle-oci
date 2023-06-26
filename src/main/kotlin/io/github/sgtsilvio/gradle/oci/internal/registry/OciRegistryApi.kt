package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.MANIFEST_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.calculateOciDigest
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpHeaders
import org.apache.commons.codec.binary.Hex
import org.reactivestreams.Publisher
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxOperator
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.ByteBufMono
import reactor.netty.NettyOutbound
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import reactor.netty.resources.ConnectionProvider
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.DigestException
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

val connectionProvider = ConnectionProvider.builder("custom").maxConnections(100).maxIdleTime(Duration.ofSeconds(2)).build()

/**
 * @author Silvio Giebl
 */
class OciRegistryApi {

    private val httpClient: HttpClient =
        HttpClient.create(
//            ConnectionProvider.builder("custom").maxIdleTime(Duration.ofSeconds(5000)).build()
            connectionProvider
        )
            .followRedirect(true)
//            .observe { connection, newState ->
//                if (newState == ConnectionObserver.State.ACQUIRED)
//                    println("$connection -> REUSE")
//                if (newState == ConnectionObserver.State.CONNECTED)
//                    println("$connection -> NEW")
//                if (newState == ConnectionObserver.State.RELEASED)
//                    println("$connection -> SAFE")
//                if (newState == ConnectionObserver.State.DISCONNECTING)
//                    println("$connection -> FAIL")
//            }
    private val tokenCache: Cache<TokenCacheKey, String> =
        Caffeine.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).build()

    private data class TokenCacheKey(val registry: String, val imageName: String, val credentials: Credentials?)

    data class Credentials(val username: String, val password: String)

    class Manifest(val mediaType: String, val data: ByteArray)

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
            {
                headers { headers ->
                    headers["accept"] =
                        "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
                }.get()
            },
        ) { response, body ->
            when (response.status().code()) {
                200 -> response.responseHeaders()["content-type"]?.let { contentType ->
                    body.aggregate().asByteArray().map { Manifest(contentType, it) }
                } ?: createError(response, body.aggregate())

                else -> createError(response, body.aggregate())
            }
        }.single()
    }

    fun pullManifest(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
    ): Mono<Manifest> = pullManifest(registry, imageName, digest.toString(), credentials).handle { manifest, sink ->
        val manifestBytes = manifest.data
        val actualDigest = manifestBytes.calculateOciDigest(digest.algorithm)
        when {
            digest != actualDigest -> sink.error(digestMismatchException(digest.hash, actualDigest.hash))
            size != manifestBytes.size.toLong() -> sink.error(sizeMismatchException(size, manifestBytes.size.toLong()))
            else -> sink.next(manifest)
        }
    }

    fun <T> pullBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
        bodyMapper: ByteBufFlux.() -> Publisher<T>,
    ): Flux<T> {
        return send(
            registry,
            imageName,
            "blobs/$digest",
            credentials,
            PULL_PERMISSION,
            { get() },
        ) { response, body ->
            when (response.status().code()) {
                200 -> ByteBufFlux.fromInbound(body.verify(digest, size)).bodyMapper()
                else -> createError(response, body.aggregate())
            }
        }
    }

    fun pullBlobAsString(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        credentials: Credentials?,
    ): Mono<String> {
        return pullBlob(
            registry,
            imageName,
            digest,
            size,
            credentials,
        ) { aggregate().asString(StandardCharsets.UTF_8) }.single()
    }

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
            { head() },
        ) { response, body ->
            when (response.status().code()) {
                200 -> body.then(Mono.just(true))
                404 -> body.then(Mono.just(false))
                else -> createError(response, body.aggregate())
            }
        }.single()
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
            { post() },
        ) { response, body ->
            when (response.status().code()) {
                201 -> if (isMount) body.then(Mono.empty()) else createError(response, body.aggregate())
                202 -> response.responseHeaders()["location"]?.let { location ->
                    body.then(Mono.just(URI(registry).resolve(location)))
                } ?: createError(response, body.aggregate())

                else -> createError(response, body.aggregate())
            }
        }.singleOrEmpty()
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
        size: Long,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Unit> {
        return send(
            registry,
            imageName,
            credentials,
            PUSH_PERMISSION,
            {
                headers { headers ->
                    headers["content-length"] = size
                    headers["content-type"] = "application/octet-stream"
                }.put().uri(uri.addQueryParam("digest=$digest")).send { _, outbound -> outbound.sender() }
            },
        ) { response, body ->
            when (response.status().code()) {
                201 -> body.then(Mono.just(Unit))
                else -> createError(response, body.aggregate())
            }
        }.single()
    }

    fun mountOrPushBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        sourceImageName: String?,
        credentials: Credentials?,
        size: Long,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Unit> = mountBlobOrCreatePushUrl(registry, imageName, digest, sourceImageName, credentials).flatMap { uri ->
        when (uri) {
            null -> Mono.just(Unit)
            else -> pushBlob(registry, imageName, digest, credentials, uri, size, sender)
        }
    }

    fun pushBlobIfNotPresent(
        registry: String,
        imageName: String,
        digest: OciDigest,
        sourceImageName: String?,
        credentials: Credentials?,
        size: Long,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Unit> = isBlobPresent(registry, imageName, digest, credentials).flatMap { present ->
        when {
            present -> Mono.just(Unit)
            else -> mountOrPushBlob(registry, imageName, digest, sourceImageName, credentials, size, sender)
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
            {
                headers { headers ->
                    headers["content-length"] = manifest.data.size
                    headers["content-type"] = manifest.mediaType
                }.put().send { _, outbound -> outbound.sendByteArray(Mono.just(manifest.data)) }
            }
        ) { response, body ->
            when (response.status().code()) {
                201 -> body.then(Mono.just(Unit))
                else -> createError(response, body.aggregate())
            }
        }.single()
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
        requestAction: HttpClient.() -> HttpClient.ResponseReceiver<*>,
        responseAction: (HttpClientResponse, ByteBufFlux) -> Publisher<T>,
    ) = send(
        registry,
        imageName,
        credentials,
        permission,
        { requestAction().uri("$registry/v2/$imageName/$path") },
        responseAction,
    )

    private fun <T> send(
        registry: String,
        imageName: String,
        credentials: Credentials?,
        permission: String,
        requestAction: HttpClient.() -> HttpClient.ResponseReceiver<*>,
        responseAction: (HttpClientResponse, ByteBufFlux) -> Publisher<T>,
    ): Flux<T> {
        return httpClient.headers { headers ->
            getAuthorization(registry, imageName, credentials)?.let { headers["authorization"] = it }
        }.requestAction().response(responseAction).onErrorResume { error ->
            when {
                error !is HttpResponseException -> Mono.error(error)
                error.statusCode != 401 -> Mono.error(error)
                else -> tryAuthorize(error.headers, registry, imageName, credentials, permission)?.flatMapMany { authorization ->
                    httpClient.headers { headers ->
                        headers["authorization"] = authorization
                    }.requestAction().response(responseAction)
                } ?: Mono.error(error)
            }
        }
    }

    private fun tryAuthorize(
        responseHeaders: HttpHeaders,
        registry: String,
        imageName: String,
        credentials: Credentials?,
        permission: String,
    ): Mono<String>? {
        val bearerParams = decodeBearerParams(responseHeaders) ?: return null
        val realm = bearerParams["realm"] ?: return null
        val service = bearerParams["service"] ?: registry
        val scope = bearerParams["scope"] ?: "repository:$imageName:$permission"
        val scopeParams = "scope=" + scope.replace(" ", "&scope=")
        return httpClient.headers { headers ->
            if (credentials != null) {
                headers["authorization"] = encodeBasicAuthorization(credentials)
            }
        }.get().uri(URI("$realm?service=$service&$scopeParams")).responseSingle { response, body ->
            when (response.status().code()) {
                200 -> body.asString(StandardCharsets.UTF_8)
                else -> createError(response, body)
            }
        }.map { response ->
            val authorization = "Bearer " + jsonObject(response).run {
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

    private fun decodeBearerParams(headers: HttpHeaders): Map<String, String>? {
        val authHeader = headers["www-authenticate"] ?: return null
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

    private fun <T> createError(response: HttpClientResponse, body: ByteBufMono): Mono<T> =
        body.asString(StandardCharsets.UTF_8).defaultIfEmpty("").flatMap { errorBody ->
            Mono.error(HttpResponseException(response.status().code(), response.responseHeaders(), errorBody))
        }
}

class DigestVerifyingFlux(
    source: Flux<ByteBuf>,
    private val messageDigest: MessageDigest,
    private val expectedDigest: ByteArray,
    private val expectedSize: Long,
) : FluxOperator<ByteBuf, ByteBuf>(source) {
    override fun subscribe(actual: CoreSubscriber<in ByteBuf>) {
        source.subscribe(Subscriber(actual, messageDigest, expectedDigest, expectedSize))
    }

    private class Subscriber(
        private val subscriber: CoreSubscriber<in ByteBuf>,
        private val messageDigest: MessageDigest,
        private val expectedDigest: ByteArray,
        private val expectedSize: Long,
    ) : CoreSubscriber<ByteBuf>, Subscription {
        private lateinit var subscription: Subscription
        private var actualSize = 0L
        private var done = false

        override fun onSubscribe(subscription: Subscription) {
            this.subscription = subscription
            subscriber.onSubscribe(this)
        }

        override fun onNext(byteBuf: ByteBuf) {
            if (done) return
            messageDigest.update(byteBuf.nioBuffer())
            actualSize += byteBuf.readableBytes()
            if (actualSize >= expectedSize) {
                if (actualSize > expectedSize) {
                    subscription.cancel()
                    done = true
                    subscriber.onError(sizeMismatchException(expectedSize, actualSize))
                    return
                }
                val actualDigest = messageDigest.digest()
                if (!expectedDigest.contentEquals(actualDigest)) {
                    subscription.cancel()
                    done = true
                    subscriber.onError(digestMismatchException(expectedDigest, actualDigest))
                    return
                }
            }
            subscriber.onNext(byteBuf)
        }

        override fun onError(error: Throwable?) {
            if (done) return else done = true
            subscriber.onError(error)
        }

        override fun onComplete() {
            if (done) return else done = true
            if (actualSize < expectedSize) {
                subscriber.onError(sizeMismatchException(expectedSize, actualSize))
            } else {
                subscriber.onComplete()
            }
        }

        override fun currentContext() = subscriber.currentContext()

        override fun request(n: Long) = subscription.request(n)

        override fun cancel() = subscription.cancel()
    }
}

private fun digestMismatchException(expectedDigest: ByteArray, actualDigest: ByteArray): DigestException {
    val expectedHex = Hex.encodeHexString(expectedDigest)
    val actualHex = Hex.encodeHexString(actualDigest)
    return DigestException("expected and actual digests do not match (expected: $expectedHex, actual: $actualHex)")
}

private fun sizeMismatchException(expectedSize: Long, actualSize: Long) =
    DigestException("expected and actual size do not match (expected: $expectedSize, actual: $actualSize)")

fun Flux<ByteBuf>.verify(digest: OciDigest, size: Long) =
    DigestVerifyingFlux(this, digest.algorithm.createMessageDigest(), digest.hash, size)

class HttpResponseException(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: String,
) : RuntimeException("unexpected http response", null, false, false) {
    override val message get() = super.message + ": " + responseToString()

    private fun responseToString() = buildString {
        append(statusCode).append('\n')
        for ((key, value) in headers.iteratorCharSequence()) {
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
