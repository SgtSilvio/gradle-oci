package io.github.sgtsilvio.gradle.oci.internal.registry

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.github.sgtsilvio.gradle.oci.internal.cache.getIfPresentMono
import io.github.sgtsilvio.gradle.oci.internal.cache.getMono
import io.github.sgtsilvio.gradle.oci.internal.json.getString
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpHeaders
import org.apache.commons.codec.binary.Hex
import org.reactivestreams.Publisher
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxOperator
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
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

    class Manifest(val mediaType: String, val data: ByteArray, val digest: OciDigest)

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
                    headers[HttpHeaderNames.ACCEPT] =
                        "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
                }.get()
            },
        ) { response, body ->
            when (response.status().code()) {
                200 -> response.responseHeaders()[HttpHeaderNames.CONTENT_TYPE]?.let { contentType ->
                    body.aggregate().asByteArray().map { data ->
                        val digestAlgorithm =
                            response.responseHeaders()["docker-content-digest"]?.toOciDigest()?.algorithm
                                ?: OciDigestAlgorithm.SHA_256
                        Manifest(contentType, data, data.calculateOciDigest(digestAlgorithm))
                    }
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
        val actualDigest =
            if (manifest.digest.algorithm == digest.algorithm) manifest.digest
            else manifestBytes.calculateOciDigest(digest.algorithm)
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
                200 -> body.then(true.toMono())
                404 -> body.then(false.toMono())
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
    ): Mono<URI> { // TODO do not retry mounting blob if not allowed (insufficient scopes)
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
                202 -> response.responseHeaders()[HttpHeaderNames.LOCATION]?.let { location ->
                    body.then(URI(registry).resolve(location).toMono())
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
//    ): Mono<Nothing> {
//        return send(
//            registry,
//            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_DELETE_ACTIONS)),
//            credentials,
//            { delete().uri(uri) },
//        ) { response, body ->
//            when (response.status().code()) {
//                204 -> body.then(Mono.empty<Nothing>())
//                else -> createError(response, body.aggregate())
//            }
//        }.singleOrEmpty()
//    }

    fun pushBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        uri: URI,
        credentials: Credentials?,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Nothing> {
        return send(
            registry,
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PUSH_ACTIONS)),
            credentials,
            {
                headers { headers ->
                    headers[HttpHeaderNames.CONTENT_LENGTH] = size
                    headers[HttpHeaderNames.CONTENT_TYPE] = HttpHeaderValues.APPLICATION_OCTET_STREAM
                }.put().uri(uri.addQueryParam("digest=$digest")).send { _, outbound -> outbound.sender() }
            },
        ) { response, body ->
            when (response.status().code()) {
                201 -> body.then(Mono.empty<Nothing>())
                else -> createError(response, body.aggregate())
            }
        }.singleOrEmpty()
    }

    fun mountOrPushBlob(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        sourceImageName: String?,
        credentials: Credentials?,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Nothing> =
        mountBlobOrCreatePushUrl(registry, imageName, digest, sourceImageName, credentials).flatMap { uri ->
            pushBlob(registry, imageName, digest, size, uri, credentials, sender)
        }

    fun pushBlobIfNotPresent(
        registry: String,
        imageName: String,
        digest: OciDigest,
        size: Long,
        sourceImageName: String?,
        credentials: Credentials?,
        sender: NettyOutbound.() -> Publisher<Void>,
    ): Mono<Nothing> {
        return if ((sourceImageName == null) || (sourceImageName == imageName)) {
            mountOrPushBlob(registry, imageName, digest, size, imageName, credentials, sender)
        } else isBlobPresent(registry, imageName, digest, credentials).flatMap { present ->
            when {
                present -> Mono.empty()
                else -> mountOrPushBlob(registry, imageName, digest, size, sourceImageName, credentials, sender)
            }
        }
    }

    fun pushManifest(
        registry: String,
        imageName: String,
        reference: String,
        mediaType: String,
        data: ByteArray,
        credentials: Credentials?,
    ): Mono<Nothing> {
        return send(
            registry,
            imageName,
            "manifests/$reference",
            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_PUSH_ACTIONS)),
            credentials,
            {
                headers { headers ->
                    headers[HttpHeaderNames.CONTENT_LENGTH] = data.size
                    headers[HttpHeaderNames.CONTENT_TYPE] = mediaType
                }.put().send { _, outbound -> outbound.sendByteArray(data.toMono()) }
            }
        ) { response, body ->
            when (response.status().code()) {
                201 -> body.then(Mono.empty<Nothing>())
                else -> createError(response, body.aggregate())
            }
        }.singleOrEmpty()
    }

    fun pushManifest(
        registry: String,
        imageName: String,
        digest: OciDigest,
        mediaType: String,
        data: ByteArray,
        credentials: Credentials?,
    ): Mono<Nothing> = pushManifest(registry, imageName, digest.toString(), mediaType, data, credentials)

//    fun deleteBlob(
//        registry: String,
//        imageName: String,
//        digest: OciDigest,
//        credentials: Credentials?,
//    ): Mono<Nothing> {
//        return send(
//            registry,
//            imageName,
//            "blobs/$digest",
//            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_DELETE_ACTIONS)),
//            credentials,
//            { delete() }
//        ) { response, body ->
//            when (response.status().code()) {
//                202 -> body.then(Mono.empty<Nothing>())
//                else -> createError(response, body.aggregate())
//            }
//        }.singleOrEmpty()
//    }
//
//    fun deleteManifest(
//        registry: String,
//        imageName: String,
//        reference: String,
//        credentials: Credentials?,
//    ): Mono<Nothing> {
//        return send(
//            registry,
//            imageName,
//            "manifests/$reference",
//            setOf(OciRegistryResourceScope(RESOURCE_SCOPE_REPOSITORY_TYPE, imageName, RESOURCE_SCOPE_DELETE_ACTIONS)),
//            credentials,
//            { delete() },
//        ) { response, body ->
//            when (response.status().code()) {
//                202 -> body.then(Mono.empty<Nothing>())
//                else -> createError(response, body.aggregate())
//            }
//        }.singleOrEmpty()
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
                headers.set(HttpHeaderNames.AUTHORIZATION, authorization)
            }.defaultIfEmpty(headers)
        }.requestAction().response(responseAction).retryWhen(RETRY_SPEC).onErrorResume { error ->
            when {
                error !is HttpResponseException -> error.toMono()
                error.statusCode != 401 -> error.toMono()
                else -> tryAuthorize(error.headers, registry, scopes, credentials)?.flatMapMany { authorization ->
                    httpClient.headers { headers ->
                        headers[HttpHeaderNames.AUTHORIZATION] = authorization
                    }.requestAction().response(responseAction).retryWhen(RETRY_SPEC)
                } ?: error.toMono()
            }
        }
    }

    private fun tryAuthorize(
        responseHeaders: HttpHeaders,
        registry: String,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
    ): Mono<String>? {
        val bearerParams = decodeBearerParams(responseHeaders) ?: return null // TODO return parsing error
        val realm = bearerParams["realm"] ?: return IllegalArgumentException("bearer authorization header is missing 'realm'").toMono()
        val service = bearerParams["service"] ?: return IllegalArgumentException("bearer authorization header is missing 'service'").toMono()
        val scope = bearerParams["scope"] ?: return IllegalArgumentException("bearer authorization header is missing 'scope'").toMono()
        val scopesFromResponse = scope.split(' ').mapTo(HashSet()) { it.decodeToResourceScope() }
        if (scopesFromResponse != scopes) {
            return IllegalStateException("scopes do not match, required: $scopes, from bearer authorization header: $scopesFromResponse").toMono()
        }
        return tokenCache.getMono(TokenCacheKey(registry, scopes, credentials?.hashed())) { key ->
            val scopeParams = key.scopes.joinToString("&scope=", "scope=") { it.encodeToString() }
            httpClient.headers { headers ->
                if (credentials != null) {
                    headers[HttpHeaderNames.AUTHORIZATION] = credentials.encodeBasicAuthorization()
                }
            }.get().uri(URI("$realm?service=$service&$scopeParams")).responseSingle { response, body ->
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
                        key.copy(scopes = registryToken.payload.scopes),
                        CompletableFuture.completedFuture(registryToken),
                    )
                    // caffeine cache logs errors except CancellationException and TimeoutException
                    throw CancellationException("insufficient scopes, required: ${key.scopes}, granted: ${registryToken.payload.scopes}")
                }
            }
        }.map { encodeBearerAuthorization(it.jws) }
    }

    private fun getAuthorization(
        registry: String,
        scopes: Set<OciRegistryResourceScope>,
        credentials: Credentials?,
    ): Mono<String> {
        return tokenCache.getIfPresentMono(TokenCacheKey(registry, scopes, credentials?.hashed()))
            .map { encodeBearerAuthorization(it.jws) }
            .run { if (credentials == null) this else switchIfEmpty(credentials.encodeBasicAuthorization().toMono()) }
    }

    private fun Credentials.encodeBasicAuthorization() =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private fun encodeBearerAuthorization(token: String) = "Bearer $token"

    private fun decodeBearerParams(headers: HttpHeaders): Map<String, String>? {
        val authHeader = headers[HttpHeaderNames.WWW_AUTHENTICATE] ?: return null
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
            HttpResponseException(response.status().code(), response.responseHeaders(), errorBody).toMono()
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
//private val RESOURCE_SCOPE_DELETE_ACTIONS = setOf("delete")
