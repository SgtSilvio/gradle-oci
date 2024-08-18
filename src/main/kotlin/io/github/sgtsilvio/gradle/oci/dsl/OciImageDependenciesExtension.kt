package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.provider.Provider
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

interface OciImageDependencies : Named { // TODO move out to own file

//    val configuration: Configuration // TODO not in the one that is extended by the multi scope interface

    val runtime: ReferencableOciImageDependencyCollector

    fun resolve(platformSelector: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>>
}

interface OciImageDependenciesWithScopes : OciImageDependencies, DependencyConstraintFactories {

    fun scope(name: String): OciImageDependencies

    fun scope(name: String, action: Action<in OciImageDependencies>)
}

// TODO rename           OciImageDependencies ->           OciImageDependencyCollector
//      rename ResolvableOciImageDependencies -> ResolvableOciImageDependencyCollector?  weird
//                                            -> OciImageDependencyWithReferenceCollector?  slightly incorrect
//                                            -> OciImageDependencyWithReferencesCollector?  slightly incorrect
//                                            -> OciImageDependencyWithReferenceSpecsCollector?  correct but long
//                                            -> OciImageReferenceDependencyCollector?
//                                            -> ReferencedOciImageDependencyCollector?
//                                            -> ReferencableOciImageDependencyCollector? <===
//                                            -> OciImageWithReferencesDependencyCollector?
//                                            -> OciImageWithReferenceSpecsDependencyCollector? <==
//      rename OciImageDependenciesForRuntimeScope -> OciImageDependenciesScope
//      rename OciImageDependenciesForRuntime      -> OciImageDependencies
// DependencyCollector: collector of dependencies
// OciImageDependencyCollector: collector of dependencies to OCI images
// OciImage...DependencyCollector: collector of dependencies to OCI images with reference specs

/*
OciImageDependenciesForRuntime
OciImageDependenciesForRuntimeScope
OciImageDependencies
ResolvableOciImageDependencies

OciImageDependencies
OciImageDependenciesScope
OciImageDependencyCollector
ReferencableOciImageDependencyCollector
 */
