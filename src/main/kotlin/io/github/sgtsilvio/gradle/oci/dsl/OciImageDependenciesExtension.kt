package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciImageDependenciesExtension : DependencyConstraintFactories {

    fun forTestSuite(testSuite: JvmTestSuite): OciImageDependenciesForRuntime

    fun forTestSuite(testSuite: JvmTestSuite, action: Action<in OciImageDependenciesForRuntime>) =
        action.execute(forTestSuite(testSuite))

    fun forTest(testTask: TaskProvider<Test>): OciImageDependenciesForRuntime

    fun forTest(testTask: TaskProvider<Test>, action: Action<in OciImageDependenciesForRuntime>) =
        action.execute(forTest(testTask))

    // dsl syntactic sugar

    val JvmTestSuite.runtime get() = forTestSuite(this).runtime

    fun JvmTestSuite.runtimeScope(scope: String) = forTestSuite(this).runtimeScope(scope)

    // no dsl syntactic sugar for Test because is should not be used inside a lazy task configuration
}

interface OciImageDependenciesForRuntime : DependencyConstraintFactories {

    val runtime: ResolvableOciImageDependencies

    fun runtimeScope(scope: String): ResolvableOciImageDependencies
}
