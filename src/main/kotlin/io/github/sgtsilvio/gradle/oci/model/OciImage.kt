package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.provider.Provider
import java.time.Instant

/**
 * @author Silvio Giebl
 */
interface OciImage {
    val manifestFile: OciFile
    val configFile: OciFile

    val creationTime: Provider<Instant>
    val author: Provider<String>
    val platform: OciPlatform
    val user: Provider<String>
    val ports: Provider<Set<String>>
    val environment: Provider<Map<String, String>>
    val entryPoint: Provider<List<String>>
    val arguments: Provider<List<String>>
    val volumes: Provider<Set<String>>
    val workingDirectory: Provider<String>
    val stopSignal: Provider<String>
    val layers: Provider<List<OciLayer>>
    val annotations: Provider<Map<String, String>>
    val externalAnnotations: Provider<Map<String, String>>
    val manifestAnnotations: Provider<Map<String, String>>
    val externalManifestAnnotations: Provider<Map<String, String>>

//    val baseLayers { file, digest, diffid }
//    val applicationLayers { file, digest, diffid }
//    val allLayers { file, digest, diffid }
}