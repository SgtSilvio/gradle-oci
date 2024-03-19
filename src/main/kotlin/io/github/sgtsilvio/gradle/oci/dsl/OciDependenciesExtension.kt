package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciDependenciesExtension {

    fun forTestSuite(testSuite: JvmTestSuite): OciDependencies

    fun forTestSuite(testSuite: JvmTestSuite, action: Action<in OciDependencies>) =
        action.execute(forTestSuite(testSuite))

    fun forTest(testTask: TaskProvider<Test>): OciDependencies

    fun forTest(testTask: TaskProvider<Test>, action: Action<in OciDependencies>) = action.execute(forTest(testTask))

    // constraint factories

    fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint

    fun constraint(project: Project): DependencyConstraint

    fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<DependencyConstraint>

    fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        constraint(dependencyProvider.asProvider())

    // dsl syntactic sugar

    val JvmTestSuite.image get() = forTestSuite(this).image

    fun JvmTestSuite.imageScope(scope: String) = forTestSuite(this).imageScope(scope)
}

interface OciDependencies {

    val image: ResolvableOciImageDependencies

    fun imageScope(scope: String): ResolvableOciImageDependencies

    // constraint factories

    fun constraint(dependencyConstraintNotation: CharSequence): DependencyConstraint

    fun constraint(project: Project): DependencyConstraint

    fun constraint(dependencyProvider: Provider<out MinimalExternalModuleDependency>): Provider<DependencyConstraint>

    fun constraint(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>) =
        constraint(dependencyProvider.asProvider())
}
