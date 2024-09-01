package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciRegistryDataTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesExtension
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
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
