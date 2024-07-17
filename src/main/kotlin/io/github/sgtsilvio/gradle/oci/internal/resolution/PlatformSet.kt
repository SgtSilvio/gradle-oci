package io.github.sgtsilvio.gradle.oci.internal.resolution

import io.github.sgtsilvio.gradle.oci.platform.Platform

internal class PlatformSet {
    var isInfinite: Boolean private set
    private val _set = LinkedHashSet<Platform>() // linked to preserve the platform order
    val set: Set<Platform> get() = _set

    constructor(isInfinite: Boolean) {
        this.isInfinite = isInfinite
    }

    constructor(platform: Platform) {
        isInfinite = false
        _set.add(platform)
    }

    fun intersect(other: PlatformSet) {
        if (other.isInfinite) {
            return
        }
        if (isInfinite) {
            isInfinite = false
            _set.addAll(other._set)
        } else {
            _set.retainAll((other._set))
        }
    }

    fun union(other: PlatformSet) {
        if (isInfinite) {
            return
        }
        if (other.isInfinite) {
            isInfinite = true
            _set.clear()
        } else {
            _set.addAll(other._set)
        }
    }

    override fun toString() = if (isInfinite) "[<any>]" else set.toString()
}
