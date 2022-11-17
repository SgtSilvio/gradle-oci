package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.provider.Provider
import java.time.Instant

/**
 * @author Silvio Giebl
 */
interface OciLayer {
    val layerFile: OciLayerFile

    val creationTime: Provider<Instant>
    val author: Provider<String>
    val createdBy: Provider<String>
    val comment: Provider<String>
    val externalAnnotations: Provider<Map<String, String>>
}