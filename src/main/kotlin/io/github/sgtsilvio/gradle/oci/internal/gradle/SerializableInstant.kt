package io.github.sgtsilvio.gradle.oci.internal.gradle

import java.io.Serializable
import java.time.Instant

/**
 * @author Silvio Giebl
 */
class SerializableInstant(val epochSecond: Long, val nano: Int) : Serializable {
    override fun toString() = toInstant().toString()
}

internal fun Instant.toSerializableInstant() = SerializableInstant(epochSecond, nano)

internal fun SerializableInstant.toInstant(): Instant = Instant.ofEpochSecond(epochSecond, nano.toLong())
