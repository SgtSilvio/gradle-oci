package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

class OciComponentResolver {
    private val resolvableComponents = hashMapOf<Capability, ResolvableComponent>()
    private var rootResolvableComponent: ResolvableComponent? = null
    val rootComponent get() = getRootResolvableComponent().component

    fun addComponent(component: OciComponent) {
        val resolvableComponent = component.resolvable()
        if (rootResolvableComponent == null) {
            rootResolvableComponent = resolvableComponent
        }
        for (versionedCapability in component.capabilities) {
            val prevComponent = resolvableComponents.put(versionedCapability.capability, resolvableComponent)
            if (prevComponent != null) {
                throw IllegalStateException("$prevComponent and $component provide the same capability")
            }
        }
    }

    fun resolvePlatforms(): PlatformSet {
        return getRootResolvableComponent().resolvePlatforms(this)
    }

    fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
        val result = linkedSetOf<ResolvableComponent.Bundle>()
        getRootResolvableComponent().collectBundlesForPlatform(platform, result)
        return result.map { it.bundle }
    }

    fun collectCapabilities() = getRootResolvableComponent().collectCapabilities()

    private fun getRootResolvableComponent() =
        rootResolvableComponent ?: throw IllegalStateException("at least one component is required")

    private fun getResolvableComponent(capability: Capability) =
        resolvableComponents[capability] ?: throw IllegalStateException("component with capability $capability missing")

    private fun OciComponent.resolvable() = when (val b = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> ResolvableComponent.Universal(this, ResolvableComponent.Bundle(b))
        is OciComponent.PlatformBundles -> ResolvableComponent.Platforms(
            this,
            b.map.mapValues { (_, bundle) -> ResolvableComponent.Bundle(bundle) },
        )
    }

    private abstract class ResolvableComponent(val component: OciComponent) {
        private var state = State.INITIAL
        private lateinit var platforms: PlatformSet

        private enum class State { INITIAL, RESOLVING, RESOLVED }

        fun resolvePlatforms(resolver: OciComponentResolver) = when (state) {
            State.RESOLVING -> throw IllegalStateException("cycle in dependencies graph found")
            State.RESOLVED -> platforms
            State.INITIAL -> {
                state = State.RESOLVING
                platforms = doResolvePlatforms(resolver)
                state = State.RESOLVED
                platforms
            }
        }

        protected abstract fun doResolvePlatforms(resolver: OciComponentResolver): PlatformSet

        fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
            if (state != State.RESOLVED) {
                throw IllegalStateException("collectBundlesForPlatform can not be called in state $state")
            }
            doCollectBundlesForPlatform(platform, result)
        }

        protected abstract fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>)

        fun collectCapabilities(): Set<VersionedCapability> {
            if (state != State.RESOLVED) {
                throw IllegalStateException("collectBundlesForPlatform can not be called in state $state")
            }
            return doCollectCapabilities()
        }

        protected abstract fun doCollectCapabilities(): Set<VersionedCapability>

        class Universal(component: OciComponent, private val bundle: Bundle) : ResolvableComponent(component) {

            override fun doResolvePlatforms(resolver: OciComponentResolver) =
                bundle.resolvePlatforms(resolver, PlatformSet(true))

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                bundle.collectBundlesForPlatform(platform, result)

            override fun doCollectCapabilities(): Set<VersionedCapability> =
                bundle.collectCapabilities(HashSet(component.capabilities))
        }

        class Platforms(component: OciComponent, private val platformBundles: Map<Platform, Bundle>) :
            ResolvableComponent(component) {

            override fun doResolvePlatforms(resolver: OciComponentResolver): PlatformSet {
                val platforms = PlatformSet(false)
                for ((platform, bundle) in platformBundles) {
                    platforms.unionise(bundle.resolvePlatforms(resolver, PlatformSet(platform)))
                }
                return platforms
            }

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
                val bundle = platformBundles[platform]
                    ?: throw IllegalStateException("unresolved dependency for platform $platform")
                bundle.collectBundlesForPlatform(platform, result)
            }

            override fun doCollectCapabilities(): Set<VersionedCapability> {
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

        class Bundle(val bundle: OciComponent.Bundle) {
            private val dependencies = ArrayList<ResolvableComponent>(bundle.parentCapabilities.size)

            fun resolvePlatforms(resolver: OciComponentResolver, platforms: PlatformSet): PlatformSet {
                for (parentCapability in bundle.parentCapabilities) {
                    dependencies += resolver.getResolvableComponent(parentCapability)
                }
                for (dependency in dependencies) {
                    platforms.intersect(dependency.resolvePlatforms(resolver))
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
}