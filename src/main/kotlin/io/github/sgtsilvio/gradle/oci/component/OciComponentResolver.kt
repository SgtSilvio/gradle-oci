package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

class OciComponentResolver {
    private val resolvableComponents = mutableMapOf<Capability, ResolvableComponent>()
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
        for (component in resolvableComponents.values) {
            component.init(this)
        }
        return getRootResolvableComponent().resolvePlatforms()
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
        private var state = State.NONE
        private lateinit var platforms: PlatformSet

        private enum class State { NONE, INITIALIZED, RESOLVING, RESOLVED }

        fun init(resolver: OciComponentResolver) = when (state) {
            State.INITIALIZED -> Unit
            State.NONE -> {
                doInit(resolver)
                state = State.INITIALIZED
            }

            else -> throw IllegalStateException("init can not be called in state $state")
        }

        protected abstract fun doInit(resolver: OciComponentResolver)

        fun resolvePlatforms() = when (state) {
            State.RESOLVING -> throw IllegalStateException("cycle in dependencies graph found")
            State.RESOLVED -> platforms
            State.INITIALIZED -> {
                state = State.RESOLVING
                platforms = doResolvePlatforms()
                state = State.RESOLVED
                platforms
            }

            else -> throw IllegalStateException("resolvePlatforms can not be called in state $state")
        }

        protected abstract fun doResolvePlatforms(): PlatformSet

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

            override fun doInit(resolver: OciComponentResolver) = bundle.init(resolver)

            override fun doResolvePlatforms() = bundle.resolvePlatforms(PlatformSet(true))

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                bundle.collectBundlesForPlatform(platform, result)

            override fun doCollectCapabilities(): Set<VersionedCapability> =
                bundle.collectCapabilities(HashSet(component.capabilities))
        }

        class Platforms(component: OciComponent, private val platformBundles: Map<Platform, Bundle>) :
            ResolvableComponent(component) {

            override fun doInit(resolver: OciComponentResolver) {
                for (bundle in platformBundles.values) {
                    bundle.init(resolver)
                }
            }

            override fun doResolvePlatforms(): PlatformSet {
                val platforms = PlatformSet(false)
                for ((platform, bundle) in platformBundles) {
                    platforms.unionise(bundle.resolvePlatforms(PlatformSet(platform)))
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

            fun init(resolver: OciComponentResolver) {
                for (parentCapability in bundle.parentCapabilities) {
                    dependencies += resolver.getResolvableComponent(parentCapability)
                }
            }

            fun resolvePlatforms(platforms: PlatformSet): PlatformSet {
                for (dependency in dependencies) {
                    platforms.intersect(dependency.resolvePlatforms())
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

    class PlatformSet : Iterable<Platform> {
        var isInfinite: Boolean private set
        private val set = hashSetOf<Platform>()

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

        fun unionise(other: PlatformSet) {
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
}