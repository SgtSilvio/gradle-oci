package io.github.sgtsilvio.gradle.oci.dsl

/**
 * @author Silvio Giebl
 */
interface OciTaskExtension {
    // extensions for tasks that extend JavaForkOptions

    fun use(imageDependencyContainer: OciImageDependencyContainer)
}