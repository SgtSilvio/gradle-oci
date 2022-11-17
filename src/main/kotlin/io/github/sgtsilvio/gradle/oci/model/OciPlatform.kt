package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciPlatform {
    val architecture: Provider<String>
    val os: Provider<String>
    val osVersion: Provider<String>
    val osFeatures: Provider<List<String>>
    val variant: Provider<String>
}