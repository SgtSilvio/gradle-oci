package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector

/**
 * @author Silvio Giebl
 */
interface PlatformFactories {
    fun platform(
        os: String,
        architecture: String,
        variant: String = "",
        osVersion: String = "",
        osFeatures: Set<String> = emptySet(),
    ) = Platform(os, architecture, variant, osVersion, osFeatures.toSortedSet()) // TODO move toSortedSet to Platform call
}

interface PlatformSelectorFactories {
    fun platformSelector(platform: Platform) = PlatformSelector(platform)
}
