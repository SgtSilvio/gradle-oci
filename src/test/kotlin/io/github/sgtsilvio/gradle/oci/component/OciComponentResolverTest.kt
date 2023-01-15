package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.dsl.PlatformImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * @author Silvio Giebl
 */
internal class OciComponentResolverTest {

    private val amd64 = PlatformImpl("amd64", "linux", "", "", setOf())
    private val arm64v8 = PlatformImpl("arm64", "linux", "v8", "", setOf())
    private val arm32v7 = PlatformImpl("arm", "linux", "v7", "", setOf())

    @Test
    fun singleComponentWithoutPlatforms_resolvesToInfinitePlatforms() {
        val bundle = createBundle("bundle")
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            bundle,
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertTrue(platforms.isInfinite)
        assertEquals(ociComponentResolver.collectBundlesForPlatform(amd64), listOf(bundle))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(bundle))
    }

    @Test
    fun singleComponentWithPlatforms_resolvesToPlatforms() {
        val bundleAmd64 = createBundle("bundleAmd64")
        val bundleArm64v8 = createBundle("bundleArm64v8")
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            OciComponent.PlatformBundles(mapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(amd64), listOf(bundleAmd64))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(bundleArm64v8))
    }

    @Test
    fun baseComponentWithPlatforms_resolvesToPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseBundleArm64v8 = createBundle("baseBundleArm64v8")
        val baseComponent = OciComponent(
            setOf(OciComponent.Capability("org.example", "base")),
            OciComponent.PlatformBundles(mapOf(amd64 to baseBundleAmd64, arm64v8 to baseBundleArm64v8)),
            mapOf(),
        )

        val bundle = createBundle("bundle", listOf(OciComponent.Capability("org.example", "base")))
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            bundle,
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(amd64), listOf(baseBundleAmd64, bundle))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(baseBundleArm64v8, bundle))
    }

    @Test
    fun baseComponentWithoutButComponentWithPlatforms_resolvesToPlatforms() {
        val baseBundle = createBundle("baseBundle")
        val baseComponent = OciComponent(
            setOf(OciComponent.Capability("org.example", "base")),
            baseBundle,
            mapOf(),
        )

        val bundleAmd64 = createBundle("bundleAmd64", listOf(OciComponent.Capability("org.example", "base")))
        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(OciComponent.Capability("org.example", "base")))
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            OciComponent.PlatformBundles(mapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(amd64), listOf(baseBundle, bundleAmd64))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(baseBundle, bundleArm64v8))
    }

    @Test
    fun componentsWithIntersectionsOfPlatforms_resolvesIntersectionOfPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseBundleArm64v8 = createBundle("baseBundleArm64v8")
        val baseComponent = OciComponent(
            setOf(OciComponent.Capability("org.example", "base")),
            OciComponent.PlatformBundles(mapOf(amd64 to baseBundleAmd64, arm64v8 to baseBundleArm64v8)),
            mapOf(),
        )

        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(OciComponent.Capability("org.example", "base")))
        val bundleArm32v7 = createBundle("bundleArm32v7", listOf(OciComponent.Capability("org.example", "base")))
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            OciComponent.PlatformBundles(mapOf(arm64v8 to bundleArm64v8, arm32v7 to bundleArm32v7)),
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(arm64v8))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(baseBundleArm64v8, bundleArm64v8))
    }

    @Test
    fun componentsWithContradictingPlatforms_resolvesNoPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseComponent = OciComponent(
            setOf(OciComponent.Capability("org.example", "base")),
            OciComponent.PlatformBundles(mapOf(amd64 to baseBundleAmd64)),
            mapOf(),
        )

        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(OciComponent.Capability("org.example", "base")))
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            OciComponent.PlatformBundles(mapOf(arm64v8 to bundleArm64v8)),
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertTrue(platforms.toSet().isEmpty())
    }

    @Test
    fun differentBaseComponentsWithDifferentPlatforms_resolvesUnionOfPlatforms() {
        val base1BundleAmd64 = createBundle("base1BundleAmd64")
        val base1Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base1")),
            OciComponent.PlatformBundles(mapOf(amd64 to base1BundleAmd64)),
            mapOf(),
        )

        val base2BundleArm64v8 = createBundle("base2BundleArm64v8")
        val base2Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base2")),
            OciComponent.PlatformBundles(mapOf(arm64v8 to base2BundleArm64v8)),
            mapOf(),
        )

        val bundleAmd64 = createBundle("bundleAmd64", listOf(OciComponent.Capability("org.example", "base1")))
        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(OciComponent.Capability("org.example", "base2")))
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            OciComponent.PlatformBundles(mapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(base1Component)
        ociComponentResolver.addComponent(base2Component)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(amd64), listOf(base1BundleAmd64, bundleAmd64))
        assertEquals(ociComponentResolver.collectBundlesForPlatform(arm64v8), listOf(base2BundleArm64v8, bundleArm64v8))
    }

    @Test
    fun complex_resolvesPlatformsAndBundlesInRightOrder() {
        val bundle = createBundle(
            "bundle",
            listOf(
                OciComponent.Capability("org.example", "base3"),
                OciComponent.Capability("org.example", "base1"),
            ),
        )
        val component = OciComponent(
            setOf(OciComponent.Capability("org.example", "test")),
            bundle,
            mapOf(),
        )

        val base1Bundle = createBundle("base1Bundle", listOf(OciComponent.Capability("org.example", "base2")))
        val base1Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base1")),
            base1Bundle,
            mapOf(),
        )

        val base2BundleAmd64 = createBundle("base2BundleAmd64", listOf(OciComponent.Capability("org.example", "base5")))
        val base2BundleArm64v8 =
            createBundle("base2BundleArm64v8", listOf(OciComponent.Capability("org.example", "base5")))
        val base2Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base2")),
            OciComponent.PlatformBundles(mapOf(amd64 to base2BundleAmd64, arm64v8 to base2BundleArm64v8)),
            mapOf(),
        )

        val base3Bundle = createBundle("base3Bundle", listOf(OciComponent.Capability("org.example", "base4")))
        val base3Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base3")),
            base3Bundle,
            mapOf(),
        )

        val base4Bundle = createBundle("base4Bundle", listOf(OciComponent.Capability("org.example", "base5")))
        val base4Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base4")),
            base4Bundle,
            mapOf(),
        )

        val base5Bundle = createBundle("base5Bundle")
        val base5Component = OciComponent(
            setOf(OciComponent.Capability("org.example", "base5")),
            base5Bundle,
            mapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(base1Component)
        ociComponentResolver.addComponent(base2Component)
        ociComponentResolver.addComponent(base3Component)
        ociComponentResolver.addComponent(base4Component)
        ociComponentResolver.addComponent(base5Component)

        val platforms = ociComponentResolver.resolvePlatforms()
        assertFalse(platforms.isInfinite)
        assertEquals(platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(
            ociComponentResolver.collectBundlesForPlatform(amd64),
            listOf(base5Bundle, base4Bundle, base3Bundle, base2BundleAmd64, base1Bundle, bundle),
        )
        assertEquals(
            ociComponentResolver.collectBundlesForPlatform(arm64v8),
            listOf(base5Bundle, base4Bundle, base3Bundle, base2BundleArm64v8, base1Bundle, bundle),
        )
    }

    private fun createBundle(name: String, parentCapabilities: List<OciComponent.Capability> = listOf()) =
        OciComponent.Bundle(
            null,
            name,
            null,
            setOf(),
            mapOf(),
            null,
            setOf(),
            null,
            null,
            mapOf(),
            mapOf(),
            mapOf(),
            mapOf(),
            parentCapabilities,
            listOf()
        )
}