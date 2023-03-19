package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

sealed interface OciComponentResolver {

    fun addComponent(component: OciComponent)

    fun resolve(capability: Capability): ResolvedComponent

    fun resolve(component: OciComponent): ResolvedComponent

    sealed interface ResolvedComponent {
        val component: OciComponent
        val platforms: PlatformSet

        fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle>

        fun collectCapabilities(): Set<VersionedCapability>
    }
}

fun OciComponentResolver(): OciComponentResolver = OciComponentResolverImpl()

private class OciComponentResolverImpl : OciComponentResolver {
    private val resolvableComponents = hashMapOf<Capability, ResolvableComponent>()

    override fun addComponent(component: OciComponent) {
        val resolvableComponent = ResolvableComponent(component)
        for (versionedCapability in component.capabilities) {
            val prevComponent = resolvableComponents.put(versionedCapability.capability, resolvableComponent)
            if (prevComponent != null) {
                throw IllegalStateException("$prevComponent and $component provide the same capability")
            }
        }
    }

    override fun resolve(capability: Capability): ResolvedComponentImpl =
        resolvableComponents[capability]?.resolve(this)
            ?: throw IllegalStateException("component with capability $capability missing")

    override fun resolve(component: OciComponent) = resolve(component.capabilities.first().capability)
}

private class ResolvableComponent(component: OciComponent) {
    private var componentOrNullOrResolvedComponent: Any? = component

    fun resolve(resolver: OciComponentResolverImpl) = when (val component = componentOrNullOrResolvedComponent) {
        is ResolvedComponentImpl -> component
        is OciComponent -> {
            componentOrNullOrResolvedComponent = null
            val resolvedComponent = component.resolve(resolver)
            componentOrNullOrResolvedComponent = resolvedComponent
            resolvedComponent
        }

        else -> throw IllegalStateException("cycle in dependencies graph found")
    }

    private fun OciComponent.resolve(resolver: OciComponentResolverImpl) = when (val b = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> UniversalComponent(this, b.resolve(resolver))
        is OciComponent.PlatformBundles -> PlatformsComponent(
            this,
            b.map.mapValues { (_, bundle) -> bundle.resolve(resolver) },
        )
    }

    private fun OciComponent.Bundle.resolve(resolver: OciComponentResolverImpl) =
        ResolvedBundle(this, parentCapabilities.map(resolver::resolve))
}

private sealed class ResolvedComponentImpl(
    final override val component: OciComponent,
    final override val platforms: PlatformSet,
) : OciComponentResolver.ResolvedComponent {

    override fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
        val result = linkedSetOf<ResolvedBundle>()
        collectBundlesForPlatform(platform, result)
        return result.map { it.bundle }
    }

    abstract fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedBundle>)
}

private class UniversalComponent(
    component: OciComponent,
    private val bundle: ResolvedBundle,
) : ResolvedComponentImpl(component, bundle.resolvePlatforms(PlatformSet(true))) {

    override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedBundle>) =
        bundle.collectBundlesForPlatform(platform, result)

    override fun collectCapabilities(): Set<VersionedCapability> =
        bundle.collectCapabilities(HashSet(component.capabilities))
}

private class PlatformsComponent(
    component: OciComponent,
    private val platformBundles: Map<Platform, ResolvedBundle>,
) : ResolvedComponentImpl(component, PlatformSet(false).apply {
    for ((platform, bundle) in platformBundles) {
        unionise(bundle.resolvePlatforms(PlatformSet(platform)))
    }
}) {

    override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedBundle>) =
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

private class ResolvedBundle(
    val bundle: OciComponent.Bundle,
    private val dependencies: List<ResolvedComponentImpl>,
) {

    fun resolvePlatforms(platforms: PlatformSet): PlatformSet {
        for (dependency in dependencies) {
            platforms.intersect(dependency.platforms)
        }
        return platforms
    }

    fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedBundle>) {
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