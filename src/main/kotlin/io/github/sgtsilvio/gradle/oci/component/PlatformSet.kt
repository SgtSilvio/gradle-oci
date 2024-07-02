package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

class PlatformSet : Iterable<Platform> {
    var isInfinite: Boolean private set
    private val set = HashSet<Platform>()

    constructor(isInfinite: Boolean) {
        this.isInfinite = isInfinite
    }

    constructor(platform: Platform) {
        isInfinite = false
        set.add(platform)
    }

    fun intersect(other: PlatformSet) {
        if (other.isInfinite) {
            return
        }
        if (isInfinite) {
            isInfinite = false
            set.addAll(other.set)
        } else {
            set.retainAll((other.set))
        }
    }

    fun union(other: PlatformSet) {
        if (isInfinite) {
            return
        }
        if (other.isInfinite) {
            isInfinite = true
            set.clear()
        } else {
            set.addAll(other.set)
        }
    }

    override fun iterator(): Iterator<Platform> {
        if (isInfinite) {
            throw UnsupportedOperationException("iterating an infinite set is not possible")
        }
        return set.iterator()
    }
}
