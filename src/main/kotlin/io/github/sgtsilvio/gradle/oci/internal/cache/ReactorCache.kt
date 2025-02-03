package io.github.sgtsilvio.gradle.oci.internal.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.util.concurrent.CancellationException

internal fun <K : Any, V> AsyncCache<K, V>.getMono(key: K, mappingFunction: (key: K) -> Mono<V>): Mono<V> {
    return Mono.defer {
        get(key) { localKey, _ ->
            mappingFunction(localKey).wrapError().toFuture()
        }.toMono().unwrapError()
    }
}

internal fun <K : Any, V> AsyncCache<K, V>.getIfPresentMono(key: K): Mono<V> =
    Mono.defer { getIfPresent(key)?.toMono()?.unwrapError() ?: Mono.empty() }

private fun <T> Mono<T>.wrapError(): Mono<T> = onErrorMap { ErrorWrapper(it) }

private fun <T> Mono<T>.unwrapError(): Mono<T> = onErrorMap { if (it is ErrorWrapper) it.error else it }

// caffeine cache logs errors except CancellationException and TimeoutException
private class ErrorWrapper(val error: Throwable) : CancellationException() {
    override fun fillInStackTrace() = this
}
