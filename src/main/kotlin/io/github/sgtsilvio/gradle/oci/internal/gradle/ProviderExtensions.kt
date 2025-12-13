package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.provider.Provider
import java.util.*

private fun <T : Any> Provider<T>.optional(): Provider<Optional<T>> =
    map { Optional.ofNullable(it) }.orElse(Optional.empty())

internal fun <T : Any, U : Any, R : Any> Provider<T>.zipAbsentAsNull(
    other: Provider<U>,
    combiner: (T, U?) -> R?,
): Provider<R> = zip(other.optional()) { t, u -> combiner(t, u.orElse(null)) }
