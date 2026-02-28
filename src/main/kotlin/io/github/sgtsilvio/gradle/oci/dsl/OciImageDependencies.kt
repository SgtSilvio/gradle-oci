package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.image.OciImageInput
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciImageDependencies : DependencyConstraintFactories, Named {

    val runtime: OciImageDependencyCollector

    fun resolve(platformSelectorProvider: Provider<PlatformSelector>): Provider<List<OciImageInput>>
}
