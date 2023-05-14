package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * @author Silvio Giebl
 */
internal class OciComponentResolverTest {

    private val amd64 = PlatformImpl("amd64", "linux", "", "", sortedSetOf())
    private val arm64v8 = PlatformImpl("arm64", "linux", "v8", "", sortedSetOf())
    private val arm32v7 = PlatformImpl("arm", "linux", "v7", "", sortedSetOf())

    @Test
    fun singleComponentWithoutPlatforms_resolvesToInfinitePlatforms() {
        val bundle = createBundle("bundle")
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            bundle,
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertTrue(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.collectBundlesForPlatform(amd64), listOf(bundle))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(bundle))
    }

    @Test
    fun singleComponentWithPlatforms_resolvesToPlatforms() {
        val bundleAmd64 = createBundle("bundleAmd64")
        val bundleArm64v8 = createBundle("bundleArm64v8")
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(resolvedComponent.collectBundlesForPlatform(amd64), listOf(bundleAmd64))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(bundleArm64v8))
    }

    @Test
    fun baseComponentWithPlatforms_resolvesToPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseBundleArm64v8 = createBundle("baseBundleArm64v8")
        val baseComponent = OciComponent(
            ComponentId("org.example", "base", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to baseBundleAmd64, arm64v8 to baseBundleArm64v8)),
            sortedMapOf(),
        )

        val bundle = createBundle("bundle", listOf(Capability("org.example", "base")))
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            bundle,
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(resolvedComponent.collectBundlesForPlatform(amd64), listOf(baseBundleAmd64, bundle))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(baseBundleArm64v8, bundle))
    }

    @Test
    fun baseComponentWithoutButComponentWithPlatforms_resolvesToPlatforms() {
        val baseBundle = createBundle("baseBundle")
        val baseComponent = OciComponent(
            ComponentId("org.example", "base", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base"), "1.0.0")),
            baseBundle,
            sortedMapOf(),
        )

        val bundleAmd64 = createBundle("bundleAmd64", listOf(Capability("org.example", "base")))
        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(Capability("org.example", "base")))
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(resolvedComponent.collectBundlesForPlatform(amd64), listOf(baseBundle, bundleAmd64))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(baseBundle, bundleArm64v8))
    }

    @Test
    fun componentsWithIntersectionsOfPlatforms_resolvesIntersectionOfPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseBundleArm64v8 = createBundle("baseBundleArm64v8")
        val baseComponent = OciComponent(
            ComponentId("org.example", "base", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to baseBundleAmd64, arm64v8 to baseBundleArm64v8)),
            sortedMapOf(),
        )

        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(Capability("org.example", "base")))
        val bundleArm32v7 = createBundle("bundleArm32v7", listOf(Capability("org.example", "base")))
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(arm64v8 to bundleArm64v8, arm32v7 to bundleArm32v7)),
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(arm64v8))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(baseBundleArm64v8, bundleArm64v8))
    }

    @Test
    fun componentsWithContradictingPlatforms_resolvesNoPlatforms() {
        val baseBundleAmd64 = createBundle("baseBundleAmd64")
        val baseComponent = OciComponent(
            ComponentId("org.example", "base", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to baseBundleAmd64)),
            sortedMapOf(),
        )

        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(Capability("org.example", "base")))
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(arm64v8 to bundleArm64v8)),
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(baseComponent)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertTrue(resolvedComponent.platforms.toSet().isEmpty())
    }

    @Test
    fun differentBaseComponentsWithDifferentPlatforms_resolvesUnionOfPlatforms() {
        val base1BundleAmd64 = createBundle("base1BundleAmd64")
        val base1Component = OciComponent(
            ComponentId("org.example", "base1", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base1"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to base1BundleAmd64)),
            sortedMapOf(),
        )

        val base2BundleArm64v8 = createBundle("base2BundleArm64v8")
        val base2Component = OciComponent(
            ComponentId("org.example", "base2", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base2"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(arm64v8 to base2BundleArm64v8)),
            sortedMapOf(),
        )

        val bundleAmd64 = createBundle("bundleAmd64", listOf(Capability("org.example", "base1")))
        val bundleArm64v8 = createBundle("bundleArm64v8", listOf(Capability("org.example", "base2")))
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to bundleAmd64, arm64v8 to bundleArm64v8)),
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(base1Component)
        ociComponentResolver.addComponent(base2Component)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(resolvedComponent.collectBundlesForPlatform(amd64), listOf(base1BundleAmd64, bundleAmd64))
        assertEquals(resolvedComponent.collectBundlesForPlatform(arm64v8), listOf(base2BundleArm64v8, bundleArm64v8))
    }

    @Test
    fun complex_resolvesPlatformsAndBundlesInRightOrder() {
        val bundle = createBundle(
            "bundle",
            listOf(
                Capability("org.example", "base3"),
                Capability("org.example", "base1"),
            ),
        )
        val component = OciComponent(
            ComponentId("org.example", "test", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "test"), "1.0.0")),
            bundle,
            sortedMapOf(),
        )

        val base1Bundle = createBundle("base1Bundle", listOf(Capability("org.example", "base2")))
        val base1Component = OciComponent(
            ComponentId("org.example", "base1", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base1"), "1.0.0")),
            base1Bundle,
            sortedMapOf(),
        )

        val base2BundleAmd64 = createBundle("base2BundleAmd64", listOf(Capability("org.example", "base5")))
        val base2BundleArm64v8 = createBundle("base2BundleArm64v8", listOf(Capability("org.example", "base5")))
        val base2Component = OciComponent(
            ComponentId("org.example", "base2", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base2"), "1.0.0")),
            OciComponent.PlatformBundles(sortedMapOf(amd64 to base2BundleAmd64, arm64v8 to base2BundleArm64v8)),
            sortedMapOf(),
        )

        val base3Bundle = createBundle("base3Bundle", listOf(Capability("org.example", "base4")))
        val base3Component = OciComponent(
            ComponentId("org.example", "base3", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base3"), "1.0.0")),
            base3Bundle,
            sortedMapOf(),
        )

        val base4Bundle = createBundle("base4Bundle", listOf(Capability("org.example", "base5")))
        val base4Component = OciComponent(
            ComponentId("org.example", "base4", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base4"), "1.0.0")),
            base4Bundle,
            sortedMapOf(),
        )

        val base5Bundle = createBundle("base5Bundle")
        val base5Component = OciComponent(
            ComponentId("org.example", "base5", "1.0.0"),
            sortedSetOf(VersionedCapability(Capability("org.example", "base5"), "1.0.0")),
            base5Bundle,
            sortedMapOf(),
        )

        val ociComponentResolver = OciComponentResolver()
        ociComponentResolver.addComponent(component)
        ociComponentResolver.addComponent(base1Component)
        ociComponentResolver.addComponent(base2Component)
        ociComponentResolver.addComponent(base3Component)
        ociComponentResolver.addComponent(base4Component)
        ociComponentResolver.addComponent(base5Component)
        val resolvedComponent = ociComponentResolver.resolve(component)

        assertFalse(resolvedComponent.platforms.isInfinite)
        assertEquals(resolvedComponent.platforms.toSet(), setOf(amd64, arm64v8))
        assertEquals(
            resolvedComponent.collectBundlesForPlatform(amd64),
            listOf(base5Bundle, base4Bundle, base3Bundle, base2BundleAmd64, base1Bundle, bundle),
        )
        assertEquals(
            resolvedComponent.collectBundlesForPlatform(arm64v8),
            listOf(base5Bundle, base4Bundle, base3Bundle, base2BundleArm64v8, base1Bundle, bundle),
        )
    }

    private fun createBundle(name: String, parentCapabilities: List<Capability> = listOf()) = OciComponent.Bundle(
        null,
        name,
        null,
        sortedSetOf(),
        sortedMapOf(),
        null,
        sortedSetOf(),
        null,
        null,
        sortedMapOf(),
        sortedMapOf(),
        sortedMapOf(),
        sortedMapOf(),
        parentCapabilities,
        listOf()
    )
}