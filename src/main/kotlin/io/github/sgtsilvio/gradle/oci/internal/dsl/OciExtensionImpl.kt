package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciCopySpec
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.dsl.*
import io.github.sgtsilvio.gradle.oci.image.OciRegistryDataTask
import io.github.sgtsilvio.gradle.oci.internal.copyspec.OciCopySpecImpl
import io.github.sgtsilvio.gradle.oci.internal.copyspec.newOciCopySpec
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import io.github.sgtsilvio.gradle.oci.platform.PlatformFilter
import org.gradle.api.Action
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.domainObjectContainer
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciExtensionImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
) : OciExtension {

    final override val imageMapping = objectFactory.newInstance<OciImageMappingImpl>()

    final override val registries = objectFactory.newInstance<OciRegistriesImpl>(imageMapping)

    final override val imageDefinitions = objectFactory.domainObjectContainer(OciImageDefinition::class) { name ->
        objectFactory.newInstance<OciImageDefinitionImpl>(name)
    }

    final override val imageDependencies = objectFactory.domainObjectContainer(OciImageDependencies::class) { name ->
        objectFactory.newInstance<OciImageDependenciesImpl>(name)
    }

    init {
        // eagerly realize imageDefinitions because they register configurations and tasks
        imageDefinitions.all {}
    }

    final override fun registries(configuration: Action<in OciRegistries>) = configuration.execute(registries)

    final override fun imageMapping(configuration: Action<in OciImageMapping>) = configuration.execute(imageMapping)


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

    final override fun copySpec() = objectFactory.newOciCopySpec()

    final override fun copySpec(configuration: Action<in OciCopySpec>): OciCopySpecImpl {
        val copySpec = copySpec()
        configuration.execute(copySpec)
        return copySpec
    }


    private val testTaskStates = HashMap<String, TestTaskState>()
    private val testSuiteExtensions = HashMap<String, OciTestExtensionImpl>()

    private class TestTaskState(objectFactory: ObjectFactory) {
        val testArgumentProvider = OciTestArgumentProvider(objectFactory)
        var testSuiteExtension: OciTestExtensionImpl? = null
        var testTaskExtension: OciTestExtensionImpl? = null
    }

    private fun getOrCreateTestTaskState(testTask: TaskProvider<Test>) = testTaskStates.getOrPut(testTask.name) {
        val state = TestTaskState(objectFactory)
        val testArgumentProvider = state.testArgumentProvider
        testTask {
            jvmArgumentProviders += testArgumentProvider
        }
        state
    }

    final override fun of(testTask: TaskProvider<Test>): OciTestExtension {
        val state = getOrCreateTestTaskState(testTask)
        var extension = state.testTaskExtension
        if (extension == null) {
            val name = testTask.name
            val registryDataTask = taskContainer.register<OciRegistryDataTask>("${name}OciRegistryData") {
                group = TASK_GROUP_NAME
                description = "Creates a Docker registry data directory for use by the $name task."
                registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$name"))
            }
            extension = objectFactory.newInstance<OciTestExtensionImpl>(name, registryDataTask, this)
            registryDataTask {
                platformSelector.set(extension.platformSelector)
                from(extension.imageDependencyScopes)
            }
            val testSuiteExtension = state.testSuiteExtension
            if (testSuiteExtension != null) {
                registryDataTask {
                    from(testSuiteExtension.imageDependencyScopes)
                }
            }
            state.testTaskExtension = extension
            state.testArgumentProvider.from(registryDataTask)
        }
        return extension
    }

    final override fun of(testSuite: JvmTestSuite): OciTestExtension {
        val name = testSuite.name
        return testSuiteExtensions.getOrPut(name) {
            val suiteName = name.concatCamelCase("suite")
            val registryDataTask = taskContainer.register<OciRegistryDataTask>("${suiteName}OciRegistryData") {
                group = TASK_GROUP_NAME
                description = "Creates a Docker registry data directory for use by the $name suite."
                registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$suiteName"))
            }
            val extension = objectFactory.newInstance<OciTestExtensionImpl>(suiteName, registryDataTask, this)
            registryDataTask {
                platformSelector.set(extension.platformSelector)
                from(extension.imageDependencyScopes)
            }
            testSuite.targets.all {
                val testTaskState = getOrCreateTestTaskState(testTask)
                testTaskState.testSuiteExtension = extension
                val testTaskExtension = testTaskState.testTaskExtension
                if (testTaskExtension == null) {
                    testTaskState.testArgumentProvider.from(registryDataTask)
                } else {
                    testTaskExtension.registryDataTask {
                        from(extension.imageDependencyScopes)
                    }
                }
            }
            extension
        }
    }
}

internal abstract class OciTestExtensionImpl @Inject constructor(
    private val name: String,
    val registryDataTask: TaskProvider<OciRegistryDataTask>,
    private val oci: OciExtension,
    providerFactory: ProviderFactory,
) : OciTestExtension {

    // linked to preserve the insertion order
    private val scopes = LinkedHashMap<String, OciImageDependencies>()

    val imageDependencyScopes: Provider<List<OciImageDependencies>> =
        providerFactory.provider { scopes.values.toList() }

    final override val imageDependencies get() = imageDependencies("")

    final override fun imageDependencies(scope: String) = scopes.getOrPut(scope) {
        oci.imageDependencies.create(name.concatCamelCase(scope))
    }
}
