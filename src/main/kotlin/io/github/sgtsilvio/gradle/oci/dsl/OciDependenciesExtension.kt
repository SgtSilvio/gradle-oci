package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * @author Silvio Giebl
 */
interface OciDependenciesExtension : DependencyConstraintFactories {

    fun forTestSuite(testSuite: JvmTestSuite): OciDependencies

    fun forTestSuite(testSuite: JvmTestSuite, action: Action<in OciDependencies>) =
        action.execute(forTestSuite(testSuite))

    fun forTest(testTask: TaskProvider<Test>): OciDependencies

    fun forTest(testTask: TaskProvider<Test>, action: Action<in OciDependencies>) = action.execute(forTest(testTask))

    // dsl syntactic sugar

    val JvmTestSuite.image get() = forTestSuite(this).image

    fun JvmTestSuite.imageScope(scope: String) = forTestSuite(this).imageScope(scope)

    // no dsl syntactic sugar for Test because is should not be used inside a lazy task configuration
}

interface OciDependencies : DependencyConstraintFactories {

    val image: ResolvableOciImageDependencies

    fun imageScope(scope: String): ResolvableOciImageDependencies
}
