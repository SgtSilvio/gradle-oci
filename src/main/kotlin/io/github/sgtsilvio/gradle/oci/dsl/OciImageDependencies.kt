package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies : Named {

    val runtime: ReferencableOciImageDependencyCollector

    fun resolve(platformSelector: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>>
}

interface OciImageDependenciesWithScopes : OciImageDependencies, DependencyConstraintFactories {

    fun scope(name: String): OciImageDependencies

    fun scope(name: String, action: Action<in OciImageDependencies>)
}
