package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciLayerFile : OciFile {
    val diffId: Provider<String>
}