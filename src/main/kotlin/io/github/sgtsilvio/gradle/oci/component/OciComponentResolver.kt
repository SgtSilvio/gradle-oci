package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.dsl.Platform

class OciComponentResolver {
    private val resolvableComponents = mutableMapOf<OciComponent.Capability, ResolvableOciComponent>()
    private var rootResolvableComponent: ResolvableOciComponent? = null
    val rootComponent get() = getRootComponent().component

    fun addComponent(component: OciComponent) {
        val resolvableComponent = ResolvableOciComponent(component)
        if (rootResolvableComponent == null) {
            rootResolvableComponent = resolvableComponent
        }
        for (capability in component.capabilities) {
            val prevComponent = resolvableComponents.put(capability, resolvableComponent)
            if (prevComponent != null) {
                throw IllegalStateException("$prevComponent and $component provide the same capability")
            }
        }
    }

    fun resolvePlatforms(): PlatformSet {
        val rootComponent = getRootComponent()
        for ((_, component) in resolvableComponents) {
            component.init(this)
        }
        return rootComponent.resolvePlatforms()
    }

    fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> =
        getRootComponent().collectBundlesForPlatform(platform)

    private fun getRootComponent() =
        rootResolvableComponent ?: throw IllegalStateException("at least one component is required")

    private fun getComponent(capability: OciComponent.Capability): ResolvableOciComponent =
        resolvableComponents[capability] ?: throw IllegalStateException("component with capability $capability missing")

    private class ResolvableOciComponent(val component: OciComponent) {
        private val bundleOrPlatformBundles = when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
            is OciComponent.Bundle -> Bundle(bundleOrPlatformBundles, PlatformSet(true))
            is OciComponent.PlatformBundles -> PlatformBundles(bundleOrPlatformBundles)
        }

        fun init(resolver: OciComponentResolver) = bundleOrPlatformBundles.init(resolver)

        private fun getBundleForPlatforms(platforms: PlatformSet) =
            bundleOrPlatformBundles.getBundleForPlatforms(platforms)

        fun resolvePlatforms() = bundleOrPlatformBundles.resolvePlatforms()

        fun collectBundlesForPlatform(platform: Platform): List<OciComponent.Bundle> {
            val result = linkedSetOf<Bundle>()
            bundleOrPlatformBundles.collectBundlesForPlatform(platform, result)
            return result.map { it.bundle }
        }

        private sealed interface BundleOrPlatformBundles {
            fun resolvePlatforms(): PlatformSet
            fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>)
        }

        private sealed class StatefulBundleOrPlatformBundles(protected val platforms: PlatformSet) :
            BundleOrPlatformBundles {
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

            abstract fun getBundleForPlatforms(platforms: PlatformSet): BundleOrPlatformBundles

            final override fun resolvePlatforms() = when (state) {
                State.RESOLVING -> throw IllegalStateException("cycle in dependencies graph found")
                State.RESOLVED -> platforms
                State.INITIALIZED -> {
                    state = State.RESOLVING
                    doResolvePlatforms()
                    state = State.RESOLVED
                    platforms
                }

                else -> throw IllegalStateException("resolveAvailablePlatforms can not be called in state $state")
            }

            protected abstract fun doResolvePlatforms()

            final override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) {
                if (state != State.RESOLVED) {
                    throw IllegalStateException("collectBundlesForPlatform can not be called in state $state")
                }
                doCollectBundlesForPlatform(platform, result)
            }

            protected abstract fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>)
        }

        private class Bundle(val bundle: OciComponent.Bundle, platforms: PlatformSet) :
            StatefulBundleOrPlatformBundles(platforms) {
            private val dependencies = mutableListOf<BundleOrPlatformBundles>()

            override fun doInit(resolver: OciComponentResolver) {
                for (parentCapability in bundle.parentCapabilities) {
                    dependencies += resolver.getComponent(parentCapability).getBundleForPlatforms(platforms)
                }
            }

            override fun getBundleForPlatforms(platforms: PlatformSet) = this

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
        }

        private class PlatformBundles(platformBundles: OciComponent.PlatformBundles) :
            StatefulBundleOrPlatformBundles(PlatformSet(false)) {
            private val map =
                platformBundles.map.mapValues { (platform, bundle) -> Bundle(bundle, PlatformSet(platform)) }

            override fun doInit(resolver: OciComponentResolver) {
                for ((_, bundle) in map) {
                    bundle.init(resolver)
                }
            }

            override fun getBundleForPlatforms(platforms: PlatformSet) =
                if (!platforms.isInfinite && platforms.count() == 1) getBundleForPlatform(platforms.first()) else this

            override fun doResolvePlatforms() {
                for ((_, bundle) in map) {
                    platforms.unionise(bundle.resolvePlatforms())
                }
            }

            override fun doCollectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                getBundleForPlatform(platform).collectBundlesForPlatform(platform, result)

            private fun getBundleForPlatform(platform: Platform) = map[platform] ?: UnresolvedBundle
        }

        private object UnresolvedBundle : BundleOrPlatformBundles {
            override fun resolvePlatforms() = PlatformSet(false)
            override fun collectBundlesForPlatform(platform: Platform, result: LinkedHashSet<Bundle>) =
                throw IllegalStateException("unresolved dependency for platform $platform")
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