package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:OutputFile
    val digestToMetadataPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val components = mutableMapOf<OciComponent.Capability, ResolvableOciComponent>()
        var rootComponent: ResolvableOciComponent? = null
        for (file in componentFiles) {
            val component = decodeComponent(file.readText())
            val resolvableComponent = ResolvableOciComponent(component)
            if (rootComponent == null) {
                rootComponent = resolvableComponent
            }
            for (capability in component.capabilities) {
                val prevComponent = components.put(capability, resolvableComponent)
                if (prevComponent != null) {
                    throw IllegalStateException("$prevComponent and $component provide the same capability")
                }
            }
        }
        if (rootComponent == null) {
            throw IllegalStateException("componentFiles must contains at least one component json file")
        }
//        val platforms = findPlatforms(rootComponent, components)
//        val images = mutableMapOf<OciComponent.Platform, OciImage>() // TODO OciImage type
    }

    private class ResolvableOciComponent(val component: OciComponent) {
        private val bundleOrPlatformBundles = when (val bundleOrPlatformBundles = component.bundleOrPlatformBundles) {
            is OciComponent.Bundle -> Bundle(bundleOrPlatformBundles, PlatformSet(true))
            is OciComponent.PlatformBundles -> PlatformBundles(bundleOrPlatformBundles)
        }

        fun init(components: Map<OciComponent.Capability, ResolvableOciComponent>) =
            bundleOrPlatformBundles.init(components)

        fun getBundleForPlatforms(platforms: PlatformSet) = bundleOrPlatformBundles.getBundleForPlatforms(platforms)

        fun resolvePlatforms() = bundleOrPlatformBundles.resolvePlatforms()

        enum class State {
            NONE, INITIALIZED, RESOLVING, RESOLVED
        }

        sealed interface BundleOrPlatformBundles {
            fun resolvePlatforms(): PlatformSet
        }

        sealed class StatefulBundleOrPlatformBundles(protected val platforms: PlatformSet) : BundleOrPlatformBundles {
            private var state = State.NONE

            fun init(components: Map<OciComponent.Capability, ResolvableOciComponent>) {
                if (state != State.NONE) {
                    throw IllegalStateException("init can not be called in state $state")
                }
                doInit(components)
                state = State.INITIALIZED
            }

            abstract fun doInit(components: Map<OciComponent.Capability, ResolvableOciComponent>)

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

            abstract fun doResolvePlatforms()
        }

        class Bundle(val bundle: OciComponent.Bundle, platforms: PlatformSet) :
            StatefulBundleOrPlatformBundles(platforms) {
            private val dependencies = mutableListOf<BundleOrPlatformBundles>()

            override fun doInit(components: Map<OciComponent.Capability, ResolvableOciComponent>) {
                for (singleParentCapabilities in bundle.parentCapabilities) {
                    val parentComponent = getComponent(components, singleParentCapabilities)
                    dependencies += parentComponent.getBundleForPlatforms(platforms)
                }
            }

            private fun getComponent(
                components: Map<OciComponent.Capability, ResolvableOciComponent>,
                capabilities: Set<OciComponent.Capability>,
            ): ResolvableOciComponent {
                val component = components[capabilities.first()]
                    ?: throw IllegalStateException("component with capabilities $capabilities missing")
                if (!component.component.capabilities.containsAll(capabilities)) {
                    throw IllegalStateException("component with capabilities ${component.component.capabilities} does not provide all required capabilities $capabilities")
                }
                return component
            }

            override fun getBundleForPlatforms(platforms: PlatformSet) = this

            override fun doResolvePlatforms() {
                for (dependency in dependencies) {
                    platforms.intersect(dependency.resolvePlatforms())
                }
            }
        }

        class PlatformBundles(platformBundles: OciComponent.PlatformBundles) :
            StatefulBundleOrPlatformBundles(PlatformSet(true)) {
            private val map =
                platformBundles.map.mapValues { (platform, bundle) -> Bundle(bundle, PlatformSet(platform)) }

            override fun doInit(components: Map<OciComponent.Capability, ResolvableOciComponent>) {
                for ((_, bundle) in map) {
                    bundle.init(components)
                }
            }

            override fun getBundleForPlatforms(platforms: PlatformSet) =
                if (platforms.count() == 1) map[platforms.first()] ?: UnresolvedBundle else this

            override fun doResolvePlatforms() {
                for ((_, bundle) in map) {
                    platforms.unionise(bundle.resolvePlatforms())
                }
            }
        }

        object UnresolvedBundle : BundleOrPlatformBundles {
            override fun resolvePlatforms() = PlatformSet(false)
        }
    }

    class PlatformSet : Iterable<OciComponent.Platform> {
        var isInfinite: Boolean private set
        private val set = hashSetOf<OciComponent.Platform>()

        constructor(isInfinite: Boolean) {
            this.isInfinite = isInfinite
        }

        constructor(platform: OciComponent.Platform) {
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

        override fun iterator() = set.iterator()
    }

//    private fun findPlatforms(
//        component: OciComponent,
//        components: Map<OciComponent.Capability, OciComponent>,
//    ): Set<OciComponent.Platform> {
//        val platforms = mutableSetOf<OciComponent.Platform>()
//        when (component.bundleOrPlatformBundles) {
//            is OciComponent.Bundle -> {
//
//            }
//            is OciComponent.PlatformBundles -> {
//
//            }
//        }
//        for (bundle in component.bundles) {
//            when (val platform = bundle.platform) {
//                null -> {
//                    val baseImage = bundle.baseImage
//                        ?: throw IllegalStateException("root component must contain defined platforms")
//                    val baseOciComponent = components[baseImage]
//                        ?: throw IllegalStateException("componentFiles must contain all referenced components")
//                    platforms.addAll(findPlatforms(baseOciComponent, components))
//                }
//
//                else -> {
//                    var baseImage = bundle.baseImage
//                    while (baseImage != null) {
//                        val baseOciComponent = components[baseImage]
//                            ?: throw IllegalStateException("componentFiles must contain all referenced components")
//                        val baseBundle = baseOciComponent.bundles.find { it.platform == platform }
//                            ?: throw IllegalStateException("base component must provide the same platform")
//                        // TODO null platform is also allowed
//                        // TODO maybe just don't add platform
//                        baseImage = baseBundle.baseImage
//                    }
//                    platforms.add(platform)
//                }
//            }
//        }
//        return platforms
//    }
}