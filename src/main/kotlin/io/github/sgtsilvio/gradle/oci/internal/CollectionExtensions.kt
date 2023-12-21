package io.github.sgtsilvio.gradle.oci.internal

internal operator fun <T : Comparable<T>> Iterable<T>.compareTo(other: Iterable<T>): Int {
    val iterator = iterator()
    val otherIterator = other.iterator()
    while (iterator.hasNext() && otherIterator.hasNext()) {
        val result = iterator.next().compareTo(otherIterator.next())
        if (result != 0) {
            return result
        }
    }
    return if (iterator.hasNext()) 1 else if (otherIterator.hasNext()) -1 else 0
}
