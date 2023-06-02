package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciRegistryDataTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDefinition
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesContainer
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.mapping.OciImageNameMapping
import io.github.sgtsilvio.gradle.oci.mapping.OciImageNameMappingImpl
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import io.github.sgtsilvio.gradle.oci.platform.PlatformImpl
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.domainObjectContainer
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciExtensionImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
) : OciExtension {

    final override val registries = objectFactory.newInstance<OciRegistriesImpl>()

    final override val imageNameMapping = objectFactory.newInstance<OciImageNameMappingImpl>()

    final override val imageDefinitions = objectFactory.domainObjectContainer(OciImageDefinition::class) { name ->
        objectFactory.newInstance<OciImageDefinitionImpl>(name)
    }

    final override val imageDependencies =
        objectFactory.domainObjectContainer(OciImageDependenciesContainer::class) { name ->
            objectFactory.newInstance<OciImageDependenciesContainerImpl>(name)
        }

    init {
        // eagerly realize imageDefinitions because it registers configurations and tasks
        imageDefinitions.all {}
    }

    final override fun registries(configuration: Action<in OciRegistries>) = configuration.execute(registries)

    final override fun imageNameMapping(configuration: Action<in OciImageNameMapping>) =
        configuration.execute(imageNameMapping)

    final override fun platform(
        os: String,
        architecture: String,
        variant: String,
        osVersion: String,
        osFeatures: Set<String>,
    ) = PlatformImpl(os, architecture, variant, osVersion, osFeatures.toSortedSet())

    final override fun platformFilter(configuration: Action<in OciExtension.PlatformFilterBuilder>): PlatformFilter {
        val builder = objectFactory.newInstance<OciExtension.PlatformFilterBuilder>()
        configuration.execute(builder)
        return PlatformFilter(
            builder.oses.get(),
            builder.architectures.get(),
            builder.variants.get(),
            builder.osVersions.get(),
        )
    }

    final override fun PlatformFilter.or(configuration: Action<in OciExtension.PlatformFilterBuilder>) =
        or(platformFilter(configuration))

    final override fun NamedDomainObjectContainer<OciImageDependenciesContainer>.forTest(
        testTask: TaskProvider<Test>,
        action: Action<in OciImageDependenciesContainer>,
    ) {
        val name = testTask.name
        val dependenciesContainer = if (name in imageDependencies.names) {
            imageDependencies.named(name, action)
        } else {
            imageDependencies.register(name, action)
        }
        val registryDataTaskName = "${name}OciRegistryData"
        val registryDataTask = if (registryDataTaskName in taskContainer.names) {
            taskContainer.named<OciRegistryDataTask>(registryDataTaskName)
        } else {
            val registryDataTask = taskContainer.register<OciRegistryDataTask>(registryDataTaskName) {
                group = TASK_GROUP_NAME
                description = "Creates a Docker registry data directory to be used by the $name task."
                imageNameMapping.from(this@OciExtensionImpl.imageNameMapping)
            }
            testTask.configure {
                jvmArgumentProviders += OciTestArgumentProvider(objectFactory, registryDataTask)
            }
            registryDataTask
        }
        registryDataTask.configure {
            from(dependenciesContainer.get().configurations)
            registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registry/$name"))
        }
    }
}