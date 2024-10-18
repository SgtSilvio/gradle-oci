package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies : DependencyConstraintFactories, Named {

    val runtime: ReferencableOciImageDependencyCollector

    fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>>
}
