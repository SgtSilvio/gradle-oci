package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.OciRegistryDataTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.*
import io.github.sgtsilvio.gradle.oci.internal.resolution.resolveOciImageInputs
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependenciesExtensionImpl @Inject constructor(
    private val oci: OciExtension,
    private val objectFactory: ObjectFactory,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDependenciesExtension {

    private val testSuiteDependencies = HashMap<String, OciImageDependenciesWithScopesImpl>()
    private val testDependencies = HashMap<String, OciImageDependenciesWithScopesImpl>()

    final override fun forTestSuite(testSuite: JvmTestSuite) = testSuiteDependencies.getOrPut(testSuite.name) {
        val testSuiteName = testSuite.name
        val dependencies = objectFactory.newInstance<OciImageDependenciesWithScopesImpl>(testSuiteName, oci)
        val registryDataTask = taskContainer.register<OciRegistryDataTask>("${testSuiteName}OciRegistryData") {
            group = TASK_GROUP_NAME
            description = "Creates a Docker registry data directory to be used by the $testSuiteName suite."
            registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$testSuiteName"))
            from(dependencies)
        }
        testSuite.targets.all {
            testTask {
                jvmArgumentProviders += OciTestArgumentProvider(objectFactory, registryDataTask)
            }
        }
        dependencies
    }

    final override fun forTest(testTask: TaskProvider<Test>) = testDependencies.getOrPut(testTask.name) {
        val testTaskName = testTask.name
        val dependencies = objectFactory.newInstance<OciImageDependenciesWithScopesImpl>(testTaskName, oci)
        val registryDataTask = taskContainer.register<OciRegistryDataTask>("${testTaskName}OciRegistryData") {
            group = TASK_GROUP_NAME
            description = "Creates a Docker registry data directory to be used by the $testTaskName task."
            registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$testTaskName"))
            from(dependencies)
        }
        testTask {
            jvmArgumentProviders += OciTestArgumentProvider(objectFactory, registryDataTask)
        }
        dependencies
    }
}

internal abstract class OciImageDependenciesImpl @Inject constructor(
    private val name: String,
    objectFactory: ObjectFactory,
    configurationContainer: ConfigurationContainer,
) : OciImageDependencies {

    final override fun getName() = name

    final override val runtime: ReferencableOciImageDependencyCollector =
        objectFactory.newInstance<ReferencableOciImageDependencyCollectorImpl>()

    private val configuration: Configuration = configurationContainer.create(name + "OciImages").apply {
        description = "OCI image dependencies '$name'"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.apply {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            attribute(PLATFORM_ATTRIBUTE, MULTI_PLATFORM_ATTRIBUTE_VALUE)
        }
        dependencies.addAllLater(runtime.dependencies)
        dependencyConstraints.addAllLater(runtime.dependencyConstraints)
    }

    final override fun resolve(platformSelector: Provider<PlatformSelector>) =
        configuration.incoming.resolveOciImageInputs(platformSelector)
}

internal abstract class OciImageDependenciesWithScopesImpl @Inject constructor(
    private val name: String,
    private val oci: OciExtension,
    private val objectFactory: ObjectFactory,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDependenciesWithScopes {

    final override fun getName() = name

    // linked because it will be iterated
    private val scopes = LinkedHashMap<String, OciImageDependencies>()

    final override val runtime = scope("").runtime

    final override fun resolve(platformSelector: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>> {
        val resolved = objectFactory.listProperty<OciImagesTask.ImageInput>()
        for (scope in scopes.values) {
            resolved.addAll(scope.resolve(platformSelector))
        }
        return resolved
    }

    final override fun scope(name: String) = scopes.getOrPut(name) {
        oci.imageDependencies.create(this.name.concatCamelCase(name))
    }

    final override fun scope(name: String, action: Action<in OciImageDependencies>) = action.execute(scope(name))
}
