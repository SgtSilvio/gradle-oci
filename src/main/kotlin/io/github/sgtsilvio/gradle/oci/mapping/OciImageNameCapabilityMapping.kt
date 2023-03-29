package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCapability
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Nested

/**
 * @author Silvio Giebl
 */
interface OciImageNameCapabilityMapping : OciImageNameMapping {

    @get:Nested
    val customMappings: ListProperty<CustomOciImageNameCapabilityMapper>
}

fun interface CustomOciImageNameCapabilityMapper {
    fun map(
        componentCapabilities: Set<VersionedCapability>,
        allCapabilities: Set<VersionedCapability>,
    ): List<OciImageName>?
}