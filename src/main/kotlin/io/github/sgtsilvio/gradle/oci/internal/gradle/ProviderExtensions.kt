package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.provider.Provider
import java.util.*

internal fun <T, B, R> Provider<T>.zipAbsentAsNull(other: Provider<B>, combiner: (T, B?) -> R): Provider<R> =
    zip(other.map { Optional.ofNullable(it) }.orElse(Optional.empty())) { t, b -> combiner.invoke(t, b.orElse(null)) }

internal fun <T, B, R> Provider<T>.zipAbsentAsEmptySet(
    other: Provider<Set<B>>,
    combiner: (T, Set<B>) -> R,
): Provider<R> = zip(other.orElse(setOf())) { t, b -> combiner.invoke(t, b) }

internal fun <T, K, V, R> Provider<T>.zipAbsentAsEmptyMap(
    other: Provider<Map<K, V>>,
    combiner: (T, Map<K, V>) -> R,
): Provider<R> = zip(other.orElse(mapOf())) { t, b -> combiner.invoke(t, b) }