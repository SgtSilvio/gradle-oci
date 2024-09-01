package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciImageDependenciesExtension : DependencyConstraintFactories {

    fun forTestSuite(testSuite: JvmTestSuite): OciImageDependenciesWithScopes

    fun forTestSuite(testSuite: JvmTestSuite, action: Action<in OciImageDependenciesWithScopes>) =
        action.execute(forTestSuite(testSuite))

    fun forTest(testTask: TaskProvider<Test>): OciImageDependenciesWithScopes

    fun forTest(testTask: TaskProvider<Test>, action: Action<in OciImageDependenciesWithScopes>) =
        action.execute(forTest(testTask))

    // dsl syntactic sugar

    val JvmTestSuite.runtime get() = forTestSuite(this).runtime

    fun JvmTestSuite.scope(scope: String) = forTestSuite(this).scope(scope)

    fun JvmTestSuite.scope(scope: String, action: Action<in OciImageDependencies>) =
        forTestSuite(this).scope(scope, action)

    // no dsl syntactic sugar for Test because it should not be used inside a lazy task configuration
}
