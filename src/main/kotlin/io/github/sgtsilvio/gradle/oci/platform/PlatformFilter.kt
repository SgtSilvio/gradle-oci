package io.github.sgtsilvio.gradle.oci.platform

import io.github.sgtsilvio.gradle.oci.internal.compareTo
import java.util.*

/**
 * @author Silvio Giebl
 */
sealed interface PlatformFilter {
    fun or(other: PlatformFilter): PlatformFilter
    fun matches(platform: Platform?): Boolean
}

internal fun PlatformFilter(oses: Set<String>, architectures: Set<String>, variants: Set<String>, osVersions: Set<String>) =
    if ((oses.size + architectures.size + variants.size + osVersions.size) == 0) AllPlatformFilter
    else FieldPlatformFilter(oses, architectures, variants, osVersions)

internal object AllPlatformFilter : PlatformFilter {
    override fun or(other: PlatformFilter) = this
    override fun matches(platform: Platform?) = true
    override fun toString() = ""
}

private class FieldPlatformFilter(
    oses: Set<String>, // empty means all
    architectures: Set<String>, // empty means all
    variants: Set<String>, // empty means all, "" element means not set
    osVersions: Set<String>, // empty means all, "" element means not set
    //val osFeatures: Set<List<String>> => no support for filtering on osFeatures because confusing and complicated (is it and/or, all/any?)
) : PlatformFilter, Comparable<FieldPlatformFilter> {
    val oses = oses.toSortedSet().toSet()
    val architectures = architectures.toSortedSet().toSet()
    val variants = variants.toSortedSet().toSet()
    val osVersions = osVersions.toSortedSet().toSet()

    override fun or(other: PlatformFilter) = when (other) {
        is AllPlatformFilter -> AllPlatformFilter
        is FieldPlatformFilter -> OrPlatformFilter(arrayOf(this, other))
        is OrPlatformFilter -> OrPlatformFilter(arrayOf(this, *other.filters))
    }

    override fun matches(platform: Platform?) = (platform != null)
            && (oses.isEmpty() || oses.contains(platform.os))
            && (architectures.isEmpty() || architectures.contains(platform.architecture))
            && (variants.isEmpty() || variants.contains(platform.variant))
            && (osVersions.isEmpty() || osVersions.contains(platform.osVersion))

    override fun toString(): String {
        var s = "@" + if (oses.isEmpty()) "+" else oses.joinToString("+")
        if ((architectures.size + variants.size + osVersions.size) == 0) {
            return s
        }
        s += ',' + if (architectures.isEmpty()) "+" else architectures.joinToString("+")
        var needsPostfix = (oses.size == 1) && (architectures.size == 1)
        if ((variants.size + osVersions.size) == 0) {
            return if (needsPostfix) "$s,+" else s
        }
        s += ',' + if (variants.isEmpty()) "+" else variants.joinToString("+")
        needsPostfix = needsPostfix && (variants.size == 1)
        if (osVersions.isEmpty()) {
            return if (needsPostfix) "$s,+" else s
        }
        s += ',' + osVersions.joinToString("+")
        needsPostfix = needsPostfix && (osVersions.size == 1)
        return if (needsPostfix) "$s,+" else s
    }

    override fun compareTo(other: FieldPlatformFilter): Int {
        oses.compareTo(other.oses).also { if (it != 0) return it }
        architectures.compareTo(other.architectures).also { if (it != 0) return it }
        variants.compareTo(other.variants).also { if (it != 0) return it }
        return osVersions.compareTo(other.osVersions)
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is FieldPlatformFilter -> false
        oses != other.oses -> false
        architectures != other.architectures -> false
        variants != other.variants -> false
        osVersions != other.osVersions -> false
        else -> true
    }

    override fun hashCode(): Int {
        var result = oses.hashCode()
        result = 31 * result + architectures.hashCode()
        result = 31 * result + variants.hashCode()
        result = 31 * result + osVersions.hashCode()
        return result
    }
}

private class OrPlatformFilter(filters: Array<FieldPlatformFilter>) : PlatformFilter {
    val filters = normalizeFilters(filters)

    override fun or(other: PlatformFilter) = when (other) {
        is AllPlatformFilter -> AllPlatformFilter
        is FieldPlatformFilter -> OrPlatformFilter(arrayOf(*filters, other))
        is OrPlatformFilter -> OrPlatformFilter(arrayOf(*filters, *other.filters))
    }

    override fun matches(platform: Platform?) = filters.any { it.matches(platform) }

    override fun toString() = buildString {
        for (filter in filters) {
            append(filter.toString())
        }
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is OrPlatformFilter -> false
        else -> filters.contentEquals(other.filters)
    }

    override fun hashCode() = filters.contentHashCode()
}

private fun normalizeFilters(filters: Array<FieldPlatformFilter>): Array<FieldPlatformFilter> {
    fun <K> MutableMap<K, Set<String>>.merge(key: K, value: String?) {
        val valueSet = if (value == null) emptySet() else setOf(value)
        merge(key, valueSet) { a, b ->
            if (a.isEmpty() || b.isEmpty()) emptySet() else a + b
        }
    }

    val osArchVariantToOsVersions = mutableMapOf<Triple<String?, String?, String?>, Set<String>>()
    for (filter in filters) {
        val oses = filter.oses.ifEmpty { setOf(null) }
        val architectures = filter.architectures.ifEmpty { setOf(null) }
        val variants = filter.variants.ifEmpty { setOf(null) }
        val osVersions = filter.osVersions.ifEmpty { setOf(null) }
        for (os in oses) {
            for (architecture in architectures) {
                for (variant in variants) {
                    for (osVersion in osVersions) {
                        osArchVariantToOsVersions.merge(Triple(os, architecture, variant), osVersion)
                    }
                }
            }
        }
    }
    osArchVariantToOsVersions.iterator().let { iterator ->
        outer@ while (iterator.hasNext()) {
            val entry = iterator.next()
            val (os, arch, variant) = entry.key
            var osVersions = entry.value
            val osSuperset = if (os == null) arrayOfNulls(1) else arrayOf(os, null)
            val archSuperset = if (arch == null) arrayOfNulls(1) else arrayOf(arch, null)
            val variantSuperset = if (variant == null) arrayOfNulls(1) else arrayOf(variant, null)
            for (superOs in osSuperset) {
                for (superArch in archSuperset) {
                    for (superVariant in variantSuperset) {
                        if ((superOs == os) && (superArch == arch) && (superVariant == variant)) continue
                        val superOsVersions =
                            osArchVariantToOsVersions[Triple(superOs, superArch, superVariant)] ?: continue
                        if (superOsVersions.isEmpty()) {
                            iterator.remove()
                            continue@outer
                        }
                        if (osVersions.isEmpty()) continue
                        osVersions = osVersions.filterTo(mutableSetOf()) { !superOsVersions.contains(it) }
                        if (osVersions.isEmpty()) {
                            iterator.remove()
                            continue@outer
                        }
                    }
                }
            }
            entry.setValue(osVersions)
        }
    }
    val osArchOsVersionsToVariants = mutableMapOf<Triple<String?, String?, Set<String>>, Set<String>>()
    for ((osArchVariant, osVersions) in osArchVariantToOsVersions) {
        val (os, arch, variant) = osArchVariant
        osArchOsVersionsToVariants.merge(Triple(os, arch, osVersions), variant)
    }
    val osVariantsOsVersionsToArches = mutableMapOf<Triple<String?, Set<String>, Set<String>>, Set<String>>()
    for ((osArchOsVersions, variants) in osArchOsVersionsToVariants) {
        val (os, arch, osVersions) = osArchOsVersions
        osVariantsOsVersionsToArches.merge(Triple(os, variants, osVersions), arch)
    }
    val archesVariantsOsVersionsToOses = mutableMapOf<Triple<Set<String>, Set<String>, Set<String>>, Set<String>>()
    for ((osVariantsOsVersions, arches) in osVariantsOsVersionsToArches) {
        val (os, variants, osVersions) = osVariantsOsVersions
        archesVariantsOsVersionsToOses.merge(Triple(arches, variants, osVersions), os)
    }
    val normalizedFilters = TreeSet<FieldPlatformFilter>()
    for ((archesVariantsOsVersions, oses) in archesVariantsOsVersionsToOses) {
        val (arches, variants, osVersions) = archesVariantsOsVersions
        normalizedFilters.add(FieldPlatformFilter(oses, arches, variants, osVersions))
    }
    return normalizedFilters.toTypedArray()
}
