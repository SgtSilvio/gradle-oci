package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciMultiPlatformImage {
    val indexFile: OciFile

    val images: Provider<List<OciImage>>
    val annotations: Provider<Map<String, String>>
}