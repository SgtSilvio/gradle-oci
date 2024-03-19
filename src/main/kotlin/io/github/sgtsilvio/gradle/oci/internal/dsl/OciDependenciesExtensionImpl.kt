package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciRegistryDataTask
import io.github.sgtsilvio.gradle.oci.TASK_GROUP_NAME
import io.github.sgtsilvio.gradle.oci.dsl.OciDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciDependenciesExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.ResolvableOciImageDependencies
import io.github.sgtsilvio.gradle.oci.internal.concatCamelCase
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
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
internal abstract class OciDependenciesExtensionImpl @Inject constructor(
    private val oci: OciExtension,
    private val objectFactory: ObjectFactory,
    private val taskContainer: TaskContainer,
    private val projectLayout: ProjectLayout,
    private val dependencyHandler: DependencyHandler,
) : OciDependenciesExtension {

    private val testSuiteDependencies = HashMap<String, OciDependenciesImpl>()
    private val testDependencies = HashMap<String, OciDependenciesImpl>()

    final override fun forTestSuite(testSuite: JvmTestSuite) = testSuiteDependencies.getOrPut(testSuite.name) {
        val testSuiteName = testSuite.name
        val registryDataTask = taskContainer.register<OciRegistryDataTask>("${testSuiteName}OciRegistryData") {
            group = TASK_GROUP_NAME
            description = "Creates a Docker registry data directory to be used by the $testSuiteName suite."
            registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$testSuiteName"))
        }
        testSuite.targets.all {
            testTask {
                jvmArgumentProviders += OciTestArgumentProvider(objectFactory, registryDataTask)
            }
        }
        objectFactory.newInstance<OciDependenciesImpl>(testSuiteName, registryDataTask, oci)
    }

    final override fun forTest(testTask: TaskProvider<Test>) = testDependencies.getOrPut(testTask.name) {
        val testTaskName = testTask.name
        val registryDataTask = taskContainer.register<OciRegistryDataTask>("${testTaskName}OciRegistryData") {
            group = TASK_GROUP_NAME
            description = "Creates a Docker registry data directory to be used by the $testTaskName task."
            registryDataDirectory.set(projectLayout.buildDirectory.dir("oci/registries/$testTaskName"))
        }
        testTask {
            jvmArgumentProviders += OciTestArgumentProvider(objectFactory, registryDataTask)
        }
        objectFactory.newInstance<OciDependenciesImpl>(testTaskName, registryDataTask, oci)
    }

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        dependencyHandler.constraints.create(dependencyConstraintNotation)

    final override fun constraint(project: Project): DependencyConstraint =
        dependencyHandler.constraints.create(project)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyHandler.constraints.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}

internal abstract class OciDependenciesImpl @Inject constructor(
    private val name: String,
    private val registryDataTask: TaskProvider<OciRegistryDataTask>,
    private val oci: OciExtension,
    private val dependencyHandler: DependencyHandler,
) : OciDependencies {

    private val scopes = HashMap<String, ResolvableOciImageDependencies>()

    final override val image = imageScope("")

    final override fun imageScope(scope: String): ResolvableOciImageDependencies = scopes.getOrPut(scope) {
        val imageDependencies = oci.imageDependencies.create(name.concatCamelCase(scope))
        registryDataTask {
            from(imageDependencies)
        }
        imageDependencies
    }

    final override fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint =
        dependencyHandler.constraints.create(dependencyConstraintNotation)

    final override fun constraint(project: Project): DependencyConstraint =
        dependencyHandler.constraints.create(project)

    private fun constraint(dependency: MinimalExternalModuleDependency): DependencyConstraint =
        dependencyHandler.constraints.create(dependency)

    final override fun constraint(
        dependencyProvider: Provider<out MinimalExternalModuleDependency>,
    ): Provider<DependencyConstraint> = dependencyProvider.map { constraint(it) }
}
