package io.github.sgtsilvio.gradle.oci.mapping

import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input

/**
 * @author Silvio Giebl
 */
interface OciImageNameMapping {

    @get:Input
    val groupMappings: MapProperty<String, String>

    @get:Input
    val moduleMappings: MapProperty<Pair<String, String>, Pair<String, String>>

    @get:Input
    val moduleVersionMappings: MapProperty<Triple<String, String, String>, Triple<String, String, String>>

    fun mapGroup(group: String, imageNamespace: String) = groupMappings.put(group, imageNamespace)

    fun mapModule(group: String, name: String, imageNamespace: String, imageName: String) =
        moduleMappings.put(Pair(group, name), Pair(imageNamespace, imageName))

    fun mapModuleVersion(
        group: String,
        name: String,
        version: String,
        imageNamespace: String,
        imageName: String,
        imageTag: String,
    ) = moduleVersionMappings.put(Triple(group, name, version), Triple(imageNamespace, imageName, imageTag))
}