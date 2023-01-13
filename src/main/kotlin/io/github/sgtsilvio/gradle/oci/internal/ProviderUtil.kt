package io.github.sgtsilvio.gradle.oci.internal

import org.gradle.api.provider.Provider
import java.util.*

fun <T, B, R> Provider<T>.zipOptional(other: Provider<B>, combiner: (T, B?) -> R) =
    zip(other.map { Optional.ofNullable(it) }.orElse(Optional.empty())) { t, b -> combiner.invoke(t, b.orElse(null)) }