package io.github.sgtsilvio.gradle.oci.internal

import org.gradle.api.provider.Provider
import java.util.*

fun <T, B, R> Provider<T>.zipAbsentAsNull(other: Provider<B>, combiner: (T, B?) -> R) =
    zip(other.map { Optional.ofNullable(it) }.orElse(Optional.empty())) { t, b -> combiner.invoke(t, b.orElse(null)) }

fun <T, B, R> Provider<T>.zipAbsentAsEmpty(other: Provider<Set<B>>, combiner: (T, Set<B>) -> R) =
    zip(other.orElse(setOf())) { t, b -> combiner.invoke(t, b) }

fun <T, K, V, R> Provider<T>.zipAbsentAsEmpty(other: Provider<Map<K, V>>, combiner: (T, Map<K, V>) -> R) =
    zip(other.orElse(mapOf())) { t, b -> combiner.invoke(t, b) }