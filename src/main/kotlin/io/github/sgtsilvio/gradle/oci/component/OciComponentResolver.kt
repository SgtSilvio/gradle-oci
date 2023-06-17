package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

sealed interface OciComponentResolver {

    fun addComponent(component: OciComponent)

    fun resolve(capability: Coordinates): ResolvedOciComponent

    fun resolve(component: OciComponent): ResolvedOciComponent
}

fun OciComponentResolver(): OciComponentResolver = OciComponentResolverImpl()

sealed interface ResolvedOciComponent {
    val component: OciComponent
    val platforms: PlatformSet

    fun collectBundlesForPlatform(platform: Platform): List<Bundle>

    fun collectCapabilities(): Set<VersionedCoordinates>

    sealed interface Bundle {
        val bundle: OciComponent.Bundle
        val component: OciComponent
    }
}

private class OciComponentResolverImpl : OciComponentResolver {
    private val resolvableComponents = hashMapOf<Coordinates, ResolvableOciComponent>()

    override fun addComponent(component: OciComponent) {
        val resolvableComponent = ResolvableOciComponent(component)
        for (versionedCapability in component.capabilities) {
            val prevComponent = resolvableComponents.put(versionedCapability.coordinates, resolvableComponent)
            if (prevComponent != null) {
                throw IllegalStateException("$prevComponent and $component provide the same capability")
            }
        }
    }

    override fun resolve(capability: Coordinates): ResolvedOciComponentImpl =
        resolvableComponents[capability]?.resolve(this)
            ?: throw IllegalStateException("component with capability $capability missing")

    override fun resolve(component: OciComponent) = resolve(component.capabilities.first().coordinates)
}

private class ResolvableOciComponent(component: OciComponent) {
    private var componentOrNullOrResolvedComponent: Any? = component

    fun resolve(resolver: OciComponentResolverImpl) = when (val component = componentOrNullOrResolvedComponent) {
        is ResolvedOciComponentImpl -> component
        is OciComponent -> {
            componentOrNullOrResolvedComponent = null
            val resolvedComponent = component.resolve(resolver)
            componentOrNullOrResolvedComponent = resolvedComponent
            resolvedComponent
        }

        else -> throw IllegalStateException("cycle in dependencies graph found")
    }

    private fun OciComponent.resolve(resolver: OciComponentResolverImpl) = when (val b = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> UniversalResolvedOciComponent(this, b.resolve(resolver, this))
        is OciComponent.PlatformBundles -> PlatformsResolvedOciComponent(
            this,
            b.map.mapValues { (_, bundle) -> bundle.resolve(resolver, this) },
        )
    }

    private fun OciComponent.Bundle.resolve(resolver: OciComponentResolverImpl, component: OciComponent) =
        ResolvedOciBundle(this, component, parentCapabilities.map(resolver::resolve))
}

private sealed class ResolvedOciComponentImpl(
    final override val component: OciComponent,
    final override val platforms: PlatformSet,
) : ResolvedOciComponent {

    override fun collectBundlesForPlatform(platform: Platform): List<ResolvedOciComponent.Bundle> {
        val result = linkedSetOf<ResolvedOciBundle>()
        collectBundlesForPlatform(platform, result)
        return result.toList()
    }

    abstract fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedOciBundle>)
}

private class UniversalResolvedOciComponent(
    component: OciComponent,
    private val bundle: ResolvedOciBundle,
) : ResolvedOciComponentImpl(component, bundle.resolvePlatforms(PlatformSet(true))) {

    override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedOciBundle>) =
        bundle.collectBundlesForPlatform(platform, result)

    override fun collectCapabilities(): Set<VersionedCoordinates> =
        bundle.collectCapabilities(HashSet(component.capabilities))
}

private class PlatformsResolvedOciComponent(
    component: OciComponent,
    private val platformBundles: Map<Platform, ResolvedOciBundle>,
) : ResolvedOciComponentImpl(component, PlatformSet(false).apply {
    for ((platform, bundle) in platformBundles) {
        unionise(bundle.resolvePlatforms(PlatformSet(platform)))
    }
}) {

    override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedOciBundle>) =
        platformBundles[platform]?.collectBundlesForPlatform(platform, result)
            ?: throw IllegalStateException("unresolved dependency for platform $platform")

    override fun collectCapabilities(): Set<VersionedCoordinates> {
        var capabilities: HashSet<VersionedCoordinates>? = null
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

private class ResolvedOciBundle(
    override val bundle: OciComponent.Bundle,
    override val component: OciComponent,
    private val dependencies: List<ResolvedOciComponentImpl>,
) : ResolvedOciComponent.Bundle {

    fun resolvePlatforms(platforms: PlatformSet): PlatformSet {
        for (dependency in dependencies) {
            platforms.intersect(dependency.platforms)
        }
        return platforms
    }

    fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<ResolvedOciBundle>) {
        if (this !in result) {
            for (dependency in dependencies) {
                dependency.collectBundlesForPlatform(platform, result)
            }
            result += this
        }
    }

    fun collectCapabilities(capabilities: HashSet<VersionedCoordinates>): HashSet<VersionedCoordinates> {
        for (dependency in dependencies) {
            capabilities.addAll(dependency.collectCapabilities())
        }
        return capabilities
    }
}