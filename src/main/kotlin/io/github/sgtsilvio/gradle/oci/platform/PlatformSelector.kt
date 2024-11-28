package io.github.sgtsilvio.gradle.oci.platform

import io.github.sgtsilvio.gradle.oci.internal.resolution.PlatformSet

/**
 * @author Silvio Giebl
 */
sealed class PlatformSelector {

    fun and(other: PlatformSelector): PlatformSelector = AndPlatformSelector(this, other)

    fun or(other: PlatformSelector): PlatformSelector = OrPlatformSelector(this, other)

    internal abstract fun select(platformSet: PlatformSet): Set<Platform>
    // PlatformFilter as argument?
    // return nullable? null -> not fulfilled, empty -> fulfilled with empty

    internal open fun singlePlatformOrNull(): Platform? = null
}

fun PlatformSelector(platform: Platform): PlatformSelector = SinglePlatformSelector(platform)

fun PlatformSelector.and(platform: Platform) = and(PlatformSelector(platform))

fun PlatformSelector.or(platform: Platform) = or(PlatformSelector(platform))

private class SinglePlatformSelector(private val platform: Platform): PlatformSelector() {

    override fun select(platformSet: PlatformSet) =
        if (platformSet.isInfinite || (platform in platformSet.set)) setOf(platform) else emptySet()

    override fun toString() = platform.toString()

    override fun singlePlatformOrNull() = platform
}

private class AndPlatformSelector(
    private val left: PlatformSelector,
    private val right: PlatformSelector,
) : PlatformSelector() {

    override fun select(platformSet: PlatformSet): Set<Platform> {
        val leftResult = left.select(platformSet)
        if (leftResult.isEmpty()) {
            return emptySet()
        }
        val rightResult = right.select(platformSet)
        if (rightResult.isEmpty()) {
            return emptySet()
        }
        return leftResult + rightResult
    }

    override fun toString() = "($left and $right)"
}

private class OrPlatformSelector(
    private val left: PlatformSelector,
    private val right: PlatformSelector,
) : PlatformSelector() {

    override fun select(platformSet: PlatformSet): Set<Platform> {
        val leftResult = left.select(platformSet)
        if (leftResult.isNotEmpty()) {
            return leftResult
        }
        val rightResult = right.select(platformSet)
        if (rightResult.isNotEmpty()) {
            return rightResult
        }
        return emptySet()
    }

    override fun toString() = "($left or $right)"
}

//PlatformFilterSelector?
//MultiPlatformSelector instead of AndPlatformSelector and SinglePlatformSelector?

/*
fallback arch:
linux,arm64 or linux,amd64

multiple arch, fallback for specific:
linux,amd64 and optional(linux,arm64)
=> linux,amd64 and (linux,arm64 or linux,amd64)?
=> linux,amd64 and (linux,arm64 or empty)?

all linux

fallback arch, multiple os:
(linux,arm64 or linux,amd64) and (windows,arm64 or windows,amd64)

multiple arch, fallback for specific, multiple os:
(linux,amd64 and (linux,arm64 or linux,amd64)) and (windows,amd64 and (windows,arm64 or windows,amd64))
=> linux,amd64 and (linux,arm64 or linux,amd64) and windows,amd64 and (windows,arm64 or windows,amd64)

???
(linux,arm64 or (linux,amd64 and linux,arm)) and (windows,arm64 or (windows,amd64 and windows,arm))

(x or y) and z
(x and z) or (y and z)
 */
