package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform

class OciComponentResolver {
    private val resolvableComponents = mutableMapOf<Capability, ResolvableComponent>()
    private var rootResolvableComponent: ResolvableComponent? = null
    val rootComponent get() = getRootResolvableComponent().component

    fun addComponent(component: OciComponent) {
        val resolvableComponent = ResolvableComponent(component)
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

    fun collectBundlesForPlatform(platform: Platform) = getRootResolvableComponent().collectBundlesForPlatform(platform)

    fun collectCapabilities() = getRootResolvableComponent().collectCapabilities()

    private fun getRootResolvableComponent() =
        rootResolvableComponent ?: throw IllegalStateException("at least one component is required")

    private fun getComponent(capability: Capability) =
        resolvableComponents[capability] ?: throw IllegalStateException("component with capability $capability missing")

    private class ResolvableComponent(val component: OciComponent) {
        private val bundleOrPlatformBundles = when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
            is OciComponent.Bundle -> Bundle(bundleOrPlatformBundles, PlatformSet(true))
            is OciComponent.PlatformBundles -> PlatformBundles(bundleOrPlatformBundles)
        }

        fun init(resolver: OciComponentResolver) = bundleOrPlatformBundles.init(resolver)

        fun resolvePlatforms() = bundleOrPlatformBundles.resolvePlatforms()

        fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
            val result = linkedSetOf<Bundle>()
            collectBundlesForPlatform(platform, result)
            return result.map { it.bundle }
        }

        fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
            bundleOrPlatformBundles.collectBundlesForPlatform(platform, result)

        fun collectCapabilities(): Set<VersionedCapability> {
            return component.capabilities + bundleOrPlatformBundles.collectCapabilities()
        }

        private sealed class BundleOrPlatformBundles(protected val platforms: PlatformSet) {
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

            abstract fun collectCapabilities(): Set<VersionedCapability>
        }

        private class Bundle(val bundle: OciComponent.Bundle, platforms: PlatformSet) :
            BundleOrPlatformBundles(platforms) {
            private val dependencies = mutableListOf<ResolvableComponent>()

            override fun doInit(resolver: OciComponentResolver) {
                for (parentCapability in bundle.parentCapabilities) {
                    dependencies += resolver.getComponent(parentCapability)
                }
            }

            override fun doResolvePlatforms() {
                for (dependency in dependencies) {
                    platforms.intersect(dependency.resolvePlatforms())
                }
            }

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
                if (this !in result) {
                    for (dependency in dependencies) {
                        dependency.collectBundlesForPlatform(platform, result)
                    }
                    result += this
                }
            }

            override fun collectCapabilities(): Set<VersionedCapability> =
                dependencies.flatMapTo(HashSet()) { it.collectCapabilities() }
        }

        private class PlatformBundles(platformBundles: OciComponent.PlatformBundles) :
            BundleOrPlatformBundles(PlatformSet(false)) {
            private val map =
                platformBundles.map.mapValues { (platform, bundle) -> Bundle(bundle, PlatformSet(platform)) }

            override fun doInit(resolver: OciComponentResolver) {
                for ((_, bundle) in map) {
                    bundle.init(resolver)
                }
            }

            override fun doResolvePlatforms() {
                for ((_, bundle) in map) {
                    platforms.unionise(bundle.resolvePlatforms())
                }
            }

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                getBundleForPlatform(platform).collectBundlesForPlatform(platform, result)

            private fun getBundleForPlatform(platform: Platform) =
                map[platform] ?: throw IllegalStateException("unresolved dependency for platform $platform")

            override fun collectCapabilities(): Set<VersionedCapability> {
                var capabilities: HashSet<VersionedCapability>? = null
                for ((_, bundle) in map) {
                    val bundleCapabilities = bundle.collectCapabilities()
                    if (capabilities == null) {
                        capabilities = HashSet(bundleCapabilities)
                    } else {
                        capabilities.retainAll(bundleCapabilities)
                    }
                }
                return capabilities ?: emptySet()
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