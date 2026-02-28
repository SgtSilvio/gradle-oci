package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.image.OciVariantInput
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.Named
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface ParentOciImageDependencies : DependencyConstraintFactories, Named {

    val runtime: ParentOciImageDependencyCollector

    fun resolve(platformProvider: Provider<Platform>): Provider<List<OciVariantInput>>
}

interface ParentOciImageDependencyCollector : OciImageDependencyCollectorBase<Unit>
