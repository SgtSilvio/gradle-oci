package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.*
import io.github.sgtsilvio.gradle.oci.layer.DefaultOciLayerTask
import io.github.sgtsilvio.gradle.oci.layer.DockerLayerTask
import io.github.sgtsilvio.gradle.oci.layer.OciLayerTask
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciExtension : PlatformFactories, PlatformSelectorFactories {
    val layerTaskClass get() = OciLayerTask::class
    val defaultLayerTask get() = DefaultOciLayerTask::class
    val dockerLayerTaskClass get() = DockerLayerTask::class
    val imagesTaskClass get() = OciImagesTask::class
    val pushTaskClass get() = OciPushTask::class
    val pushSingleTaskClass get() = OciPushSingleTask::class
    val registryDataTaskClass get() = OciRegistryDataTask::class

    val registries: OciRegistries
    val imageMapping: OciImageMapping
    val imageDefinitions: NamedDomainObjectContainer<OciImageDefinition>
    val imageDependencies: NamedDomainObjectContainer<OciImageDependencies>

    fun registries(configuration: Action<in OciRegistries>)

    fun imageMapping(configuration: Action<in OciImageMapping>)

    // factories

    fun platformFilter(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    fun PlatformFilter.or(configuration: Action<in PlatformFilterBuilder>): PlatformFilter

    interface PlatformFilterBuilder {
        val oses: SetProperty<String>
        val architectures: SetProperty<String>
        val variants: SetProperty<String>
        val osVersions: SetProperty<String>
    }

    fun copySpec(): OciCopySpec

    fun copySpec(configuration: Action<in OciCopySpec>): OciCopySpec

    // extensions of types other than Project

    fun of(testTask: TaskProvider<Test>): OciTestExtension

    fun of(testTask: TaskProvider<Test>, action: Action<in OciTestExtension>) = action.execute(of(testTask))

    fun of(testSuite: JvmTestSuite): OciTestExtension

    fun of(testSuite: JvmTestSuite, action: Action<in OciTestExtension>) = action.execute(of(testSuite))
}

interface OciTestExtension : PlatformFactories, PlatformSelectorFactories {

    val platformSelector: Property<PlatformSelector>

    val imageDependencies: OciImageDependencies

    fun imageDependencies(action: Action<in OciImageDependencies>) = action.execute(imageDependencies)

    fun imageDependencies(scope: String): OciImageDependencies

    fun imageDependencies(scope: String, action: Action<in OciImageDependencies>) =
        action.execute(imageDependencies(scope))
}
