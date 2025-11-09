package io.github.sgtsilvio.gradle.oci.internal.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import java.util.concurrent.CancellationException

internal fun <K : Any, V : Any> AsyncCache<K, V>.getMono(key: K, mappingFunction: (key: K) -> Mono<out V>): Mono<V> {
    return Mono.defer {
        get(key) { key, _ ->
            val future = mappingFunction(key).wrapError().toFuture()
            future
        }.toMono().unwrapError()
    }
}

internal fun <K : Any, V : Any> AsyncCache<K, out V?>.getIfPresentMono(key: K): Mono<V> =
    Mono.defer { getIfPresent(key)?.toMono()?.unwrapError() ?: Mono.empty() }

private fun <T : Any> Mono<T>.wrapError(): Mono<T> = onErrorMap { ErrorWrapper(it) }

private fun <T : Any> Mono<T>.unwrapError(): Mono<T> = onErrorMap { if (it is ErrorWrapper) it.error else it }

// caffeine cache logs errors except CancellationException and TimeoutException
private class ErrorWrapper(val error: Throwable) : CancellationException() {
    override fun fillInStackTrace() = this
}
