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
        val rootComponent = getRootResolvableComponent()
        for ((_, component) in resolvableComponents) {
            component.init(this)
        }
        return rootComponent.resolvePlatforms()
    }

    fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
        val result = linkedSetOf<ResolvableComponent.Bundle>()
        getRootResolvableComponent().collectBundlesForPlatform(platform, result)
        return result.map { it.bundle }
    }

    fun collectCapabilities() = getRootResolvableComponent().collectCapabilities()

    private fun getRootResolvableComponent() =
        rootResolvableComponent ?: throw IllegalStateException("at least one component is required")

    private fun getComponent(capability: Capability) =
        resolvableComponents[capability] ?: throw IllegalStateException("component with capability $capability missing")

    private fun OciComponent.resolvable() = when (val b = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> ResolvableComponent.Universal(this, ResolvableComponent.Bundle(b))
        is OciComponent.PlatformBundles -> ResolvableComponent.Platforms(
            this,
            b.map.mapValues { (_, bundle) -> ResolvableComponent.Bundle(bundle) },
        )
    }

    private sealed class ResolvableComponent(val component: OciComponent, protected val platforms: PlatformSet) {
        private var state = State.NONE

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
                doResolvePlatforms()
                state = State.RESOLVED
                platforms
            }

            else -> throw IllegalStateException("resolvePlatforms can not be called in state $state")
        }

        protected abstract fun doResolvePlatforms()

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

        class Universal(
            component: OciComponent,
            private val bundle: Bundle,
        ) : ResolvableComponent(component, PlatformSet(true)) {

            override fun doInit(resolver: OciComponentResolver) = bundle.init(resolver)

            override fun doResolvePlatforms() = bundle.resolvePlatforms(platforms)

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                bundle.collectBundlesForPlatform(platform, result)

            override fun doCollectCapabilities() = bundle.collectCapabilities() + component.capabilities
        }

        class Platforms(
            component: OciComponent,
            private val platformBundles: Map<Platform, Bundle>,
        ) : ResolvableComponent(component, PlatformSet(false)) {

            override fun doInit(resolver: OciComponentResolver) {
                for ((_, bundle) in platformBundles) {
                    bundle.init(resolver)
                }
            }

            override fun doResolvePlatforms() {
                for ((platform, bundle) in platformBundles) {
                    val bundlePlatforms = PlatformSet(platform)
                    bundle.resolvePlatforms(bundlePlatforms)
                    platforms.unionise(bundlePlatforms)
                }
            }

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
                val bundle = platformBundles[platform]
                    ?: throw IllegalStateException("unresolved dependency for platform $platform")
                bundle.collectBundlesForPlatform(platform, result)
            }

            override fun doCollectCapabilities(): Set<VersionedCapability> {
                var capabilities: HashSet<VersionedCapability>? = null
                for ((_, bundle) in platformBundles) {
                    val bundleCapabilities = bundle.collectCapabilities()
                    if (capabilities == null) {
                        capabilities = HashSet(bundleCapabilities)
                    } else {
                        capabilities.retainAll(bundleCapabilities)
                    }
                }
                if (capabilities == null) {
                    return component.capabilities
                }
                capabilities += component.capabilities
                return capabilities
            }
        }

        class Bundle(val bundle: OciComponent.Bundle) {
            private val dependencies = mutableListOf<ResolvableComponent>()

            fun init(resolver: OciComponentResolver) {
                for (parentCapability in bundle.parentCapabilities) {
                    dependencies += resolver.getComponent(parentCapability)
                }
            }

            fun resolvePlatforms(platforms: PlatformSet) {
                for (dependency in dependencies) {
                    platforms.intersect(dependency.resolvePlatforms())
                }
            }

            fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
                if (this !in result) {
                    for (dependency in dependencies) {
                        dependency.collectBundlesForPlatform(platform, result)
                    }
                    result += this
                }
            }

            fun collectCapabilities(): Set<VersionedCapability> =
                dependencies.flatMapTo(HashSet()) { it.collectCapabilities() }
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
                set += other.set
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
                set += other.set
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