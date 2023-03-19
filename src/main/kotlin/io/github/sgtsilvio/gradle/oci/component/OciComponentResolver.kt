package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

class OciComponentResolver {
    private val resolvableComponents = hashMapOf<Capability, ResolvableComponent>()

    fun addComponent(component: OciComponent) {
        val resolvableComponent = ResolvableComponent(component)
        for (versionedCapability in component.capabilities) {
            val prevComponent = resolvableComponents.put(versionedCapability.capability, resolvableComponent)
            if (prevComponent != null) {
                throw IllegalStateException("$prevComponent and $component provide the same capability")
            }
        }
    }

    private fun resolveInternal(capability: Capability): ResolvedComponentInternal =
        resolvableComponents[capability]?.resolve(this)
            ?: throw IllegalStateException("component with capability $capability missing")

    fun resolve(capability: Capability): ResolvedComponent = resolveInternal(capability)

    fun resolve(component: OciComponent) = resolve(component.capabilities.first().capability)

    private class ResolvableComponent(component: OciComponent) {
        private var componentOrNullOrResolvedComponent: Any? = component

        fun resolve(resolver: OciComponentResolver) = when (val component = componentOrNullOrResolvedComponent) {
            is ResolvedComponentInternal -> component
            is OciComponent -> {
                componentOrNullOrResolvedComponent = null
                val resolvedComponent = component.resolve(resolver)
                componentOrNullOrResolvedComponent = resolvedComponent
                resolvedComponent
            }

            else -> throw IllegalStateException("cycle in dependencies graph found")
        }

        private fun OciComponent.resolve(resolver: OciComponentResolver) = when (val b = bundleOrPlatformBundles) {
            is OciComponent.Bundle -> UniversalComponent(this, b.resolve(resolver))
            is OciComponent.PlatformBundles -> PlatformsComponent(
                this,
                b.map.mapValues { (_, bundle) -> bundle.resolve(resolver) },
            )
        }

        private fun OciComponent.Bundle.resolve(resolver: OciComponentResolver) =
            Bundle(this, parentCapabilities.map(resolver::resolveInternal))
    }

    sealed interface ResolvedComponent {
        val component: OciComponent
        val platforms: PlatformSet

        fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle>

        fun collectCapabilities(): Set<VersionedCapability>
    }

    private sealed class ResolvedComponentInternal(
        final override val component: OciComponent,
        final override val platforms: PlatformSet,
    ) : ResolvedComponent {

        override fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
            val result = linkedSetOf<Bundle>()
            collectBundlesForPlatform(platform, result)
            return result.map { it.bundle }
        }

        abstract fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>)
    }

    private class UniversalComponent(
        component: OciComponent,
        private val bundle: Bundle,
    ) : ResolvedComponentInternal(component, bundle.resolvePlatforms(PlatformSet(true))) {

        override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
            bundle.collectBundlesForPlatform(platform, result)

        override fun collectCapabilities(): Set<VersionedCapability> =
            bundle.collectCapabilities(HashSet(component.capabilities))
    }

    private class PlatformsComponent(
        component: OciComponent,
        private val platformBundles: Map<Platform, Bundle>,
    ) : ResolvedComponentInternal(component, PlatformSet(false).apply {
        for ((platform, bundle) in platformBundles) {
            unionise(bundle.resolvePlatforms(PlatformSet(platform)))
        }
    }) {

        override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
            platformBundles[platform]?.collectBundlesForPlatform(platform, result)
                ?: throw IllegalStateException("unresolved dependency for platform $platform")

        override fun collectCapabilities(): Set<VersionedCapability> {
            var capabilities: HashSet<VersionedCapability>? = null
            for (bundle in platformBundles.values) {
                val bundleCapabilities = bundle.collectCapabilities(HashSet())
                if (capabilities == null) {
                    capabilities = bundleCapabilities
                } else {
                    capabilities.retainAll(bundleCapabilities)
                }
            }
            if (capabilities == null) {
                return HashSet(component.capabilities)
            }
            capabilities.addAll(component.capabilities)
            return capabilities
        }
    }

    private class Bundle(val bundle: OciComponent.Bundle, private val dependencies: List<ResolvedComponentInternal>) {

        fun resolvePlatforms(platforms: PlatformSet): PlatformSet {
            for (dependency in dependencies) {
                platforms.intersect(dependency.platforms)
            }
            return platforms
        }

        fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
            if (this !in result) {
                for (dependency in dependencies) {
                    dependency.collectBundlesForPlatform(platform, result)
                }
                result += this
            }
        }

        fun collectCapabilities(capabilities: HashSet<VersionedCapability>): HashSet<VersionedCapability> {
            for (dependency in dependencies) {
                capabilities.addAll(dependency.collectCapabilities())
            }
            return capabilities
        }
    }
}