package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
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
import reactor.netty.http.client.PrematureCloseException
import reactor.util.retry.Retry
import reactor.util.retry.RetrySpec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.DigestException
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * @author Silvio Giebl
 */
class OciRegistryApi(httpClient: HttpClient) {

    private val httpClient = httpClient.followRedirect(true)
    private val tokenCache: AsyncCache<TokenCacheKey, OciRegistryToken> =
        Caffeine.newBuilder().expireAfter(TokenCacheExpiry).buildAsync()

    private data class TokenCacheKey(
        val registry: String,
        val scopes: Set<OciRegistryResourceScope>,
        val credentials: HashedCredentials?,
    )

    private object TokenCacheExpiry : Expiry<TokenCacheKey, OciRegistryToken> {
        override fun expireAfterCreate(key: TokenCacheKey, value: OciRegistryToken, currentTime: Long) =
            Instant.now().until(value.payload.expirationTime!!.minusSeconds(30), ChronoUnit.NANOS).coerceAtLeast(0)

        override fun expireAfterUpdate(
            key: TokenCacheKey,
            value: OciRegistryToken,
            currentTime: Long,
            currentDuration: Long,
        ) = expireAfterCreate(key, value, currentTime)

        override fun expireAfterRead(
            key: TokenCacheKey,
            value: OciRegistryToken,
            currentTime: Long,
            currentDuration: Long,
        ) = currentDuration
    }

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
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PULL_ACTIONS)),
            credentials,
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
            size != manifestBytes.size.toLong() -> sink.error(sizeMismatchException(size, manifestBytes.size.toLong()))
            digest != actualDigest -> sink.error(digestMismatchException(digest.hash, actualDigest.hash))
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
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PULL_ACTIONS)),
            credentials,
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
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PULL_ACTIONS)),
            credentials,
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
    ): Mono<URI?> { // TODO do not retry mounting blob if not allowed (insufficient scopes)
        var query = ""
        val scopes = hashSetOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PUSH_ACTIONS))
        val isMount = digest != null
        if (isMount) {
            query += "?mount=$digest"
            if (sourceImageName != null) {
                query += "&from=$sourceImageName"
                if (sourceImageName != imageName) {
                    scopes += OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, sourceImageName, RESOURCE_SCOPE_PULL_ACTIONS)
                }
            }
        }
        return send(
            registry,
            imageName,
            "blobs/uploads/$query",
            scopes,
            credentials,
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
//    ): Mono<Unit> {
//        return send(
//            registry,
//            imageName,
//            credentials,
//            DELETE_PERMISSION,
//            { delete().uri(uri) },
//        ) { response, body ->
//            when (response.status().code()) {
//                204 -> body.then(Mono.just(Unit))
//                else -> createError(response, body.aggregate())
//            }
//        }.single()
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
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PUSH_ACTIONS)),
            credentials,
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
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PUSH_ACTIONS)),
            credentials,
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
//    ): Mono<Unit> {
//        return send(
//            registry,
//            imageName,
//            "blobs/$digest",
//            credentials,
//            DELETE_PERMISSION,
//            { delete() }
//        ) { response, body ->
//            when (response.status().code()) {
//                202 -> body.then(Mono.just(Unit))
//                else -> createError(response, body.aggregate())
//            }
//        }.single()
//    }
//
//    fun deleteManifest(
//        registry: String,
//        imageName: String,
//        reference: String,
//        credentials: Credentials?,
//    ): Mono<Unit> {
//        return send(
//            registry,
//            imageName,
//            "manifests/$reference",
//            credentials,
//            DELETE_PERMISSION,
//            { delete() },
//        ) { response, body ->
//            when (response.status().code()) {
//                202 -> body.then(Mono.just(Unit))
//                else -> createError(response, body.aggregate())
//            }
//        }.single()
//    }

    private fun <T> send(
        registry: String,
        imageName: String,
        path: String,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
        requestAction: HttpClient.() -> HttpClient.ResponseReceiver<*>,
        responseAction: (HttpClientResponse, ByteBufFlux) -> Publisher<T>,
    ) = send(
        registry,
        scopes,
        credentials,
        { requestAction().uri("$registry/v2/$imageName/$path") },
        responseAction,
    )

    private fun <T> send(
        registry: String,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
        requestAction: HttpClient.() -> HttpClient.ResponseReceiver<*>,
        responseAction: (HttpClientResponse, ByteBufFlux) -> Publisher<T>,
    ): Flux<T> {
        return httpClient.headersWhen { headers ->
            getAuthorization(registry, scopes, credentials).map { authorization ->
                headers.set("authorization", authorization)
            }.defaultIfEmpty(headers)
        }.requestAction().response(responseAction).retryWhen(RETRY_SPEC).onErrorResume { error ->
            when {
                error !is HttpResponseException -> Mono.error(error)
                error.statusCode != 401 -> Mono.error(error)
                else -> tryAuthorize(error.headers, scopes, credentials)?.flatMapMany { authorization ->
                    httpClient.headers { headers ->
                        headers["authorization"] = authorization
                    }.requestAction().response(responseAction).retryWhen(RETRY_SPEC)
                } ?: Mono.error(error)
            }
        }
    }

    private fun tryAuthorize(
        responseHeaders: HttpHeaders,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
    ): Mono<String>? {
        val bearerParams = decodeBearerParams(responseHeaders) ?: return null // TODO return parsing error
        val realm = bearerParams["realm"] ?: return Mono.error(IllegalArgumentException("bearer authorization header is missing 'realm'"))
        val service = bearerParams["service"] ?: return Mono.error(IllegalArgumentException("bearer authorization header is missing 'service'"))
        val scope = bearerParams["scope"] ?: return Mono.error(IllegalArgumentException("bearer authorization header is missing 'scope'"))
        val scopesFromResponse = scope.split(' ').mapTo(HashSet()) { it.decodeToResourceScope() }
        if (scopesFromResponse != scopes) {
            return Mono.error(IllegalStateException("scopes do not match, required: $scopes, from bearer authorization header: $scopesFromResponse"))
        }
        return Mono.fromFuture(tokenCache.get(TokenCacheKey(service, scopes, credentials?.hashed())) { key, _ ->
            val scopeParams = key.scopes.joinToString("&scope=", "scope=") { it.encodeToString() }
            httpClient.headers { headers ->
                if (credentials != null) {
                    headers["authorization"] = credentials.encodeBasicAuthorization()
                }
            }.get().uri(URI("$realm?service=${key.registry}&$scopeParams")).responseSingle { response, body ->
                when (response.status().code()) {
                    200 -> body.asString(StandardCharsets.UTF_8)
                    else -> createError(response, body)
                }
            }.retryWhen(RETRY_SPEC).map { response ->
                val jws = jsonObject(response).run {
                    if (hasKey("token")) getString("token") else getString("access_token")
                }
                val registryToken = OciRegistryToken(jws)
                if (registryToken.payload.scopes == key.scopes) {
                    registryToken
                } else {
                    tokenCache.asMap().putIfAbsent(
                        TokenCacheKey(key.registry, registryToken.payload.scopes, key.credentials),
                        CompletableFuture.completedFuture(registryToken)
                    )
                    // caffeine cache logs errors except CancellationException and TimeoutException
                    throw CancellationException("insufficient scopes, required: ${key.scopes}, granted: ${registryToken.payload.scopes}")
                }
            }.toFuture()
        }).map { encodeBearerAuthorization(it.jws) }
    }

    private fun getAuthorization(
        registry: String,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
    ): Mono<String> {
        val tokenFuture = tokenCache.getIfPresent(TokenCacheKey(registry, scopes, credentials?.hashed()))
        return when {
            tokenFuture != null -> Mono.fromFuture(tokenFuture).map { encodeBearerAuthorization(it.jws) }
            credentials != null -> Mono.just(credentials.encodeBasicAuthorization())
            else -> Mono.empty()
        }
    }

    private fun Credentials.encodeBasicAuthorization() =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private fun encodeBearerAuthorization(token: String) = "Bearer $token"

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

private val RETRY_SPEC: RetrySpec = Retry.max(3).filter { error -> error is PrematureCloseException }

fun Flux<ByteBuf>.verify(digest: OciDigest, size: Long) =
    DigestVerifyingFlux(this, digest.algorithm.createMessageDigest(), digest.hash, size)

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
private const val RESOURCE_SCOPE_REPOSITORY_TYPE = "repository"
private val RESOURCE_SCOPE_PULL_ACTIONS = setOf("pull")
private val RESOURCE_SCOPE_PUSH_ACTIONS = setOf("pull", "push")
