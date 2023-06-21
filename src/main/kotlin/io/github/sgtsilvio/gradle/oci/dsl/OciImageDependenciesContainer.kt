package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Named
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependenciesContainer : Named {

    val configurations: Provider<List<Configuration>>
    val default: OciTaggableImageDependencies

    fun scope(scope: String): OciTaggableImageDependencies
}