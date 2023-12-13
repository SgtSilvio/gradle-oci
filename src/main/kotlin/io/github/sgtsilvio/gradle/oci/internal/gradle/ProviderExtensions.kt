package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.provider.Provider
import java.util.*

internal fun <T> Provider<T>.optional(): Provider<Optional<T & Any>> =
    map { Optional.ofNullable(it) }.orElse(Optional.empty())

internal fun <T, U, R> Provider<T>.zipAbsentAsNull(other: Provider<U>, combiner: (T, U?) -> R): Provider<R> =
    zip(other.optional()) { t, u -> combiner(t, u.orElse(null)) }

internal fun <T, E, R> Provider<T>.zipAbsentAsEmptySet(
    other: Provider<Set<E>>,
    combiner: (T, Set<E>) -> R,
): Provider<R> = zip(other.orElse(setOf())) { t, set -> combiner(t, set) }

internal fun <T, K, V, R> Provider<T>.zipAbsentAsEmptyMap(
    other: Provider<Map<K, V>>,
    combiner: (T, Map<K, V>) -> R,
): Provider<R> = zip(other.orElse(mapOf())) { t, map -> combiner(t, map) }
