package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCapability

/**
 * @author Silvio Giebl
 */
class OciImageNameCapabilityMapper(
    private val mapper: OciImageNameMapper,
    private val customMappings: List<CustomOciImageNameCapabilityMapper>,
) {

    fun map(
        componentCapabilities: Set<VersionedCapability>,
        allCapabilities: Set<VersionedCapability>,
    ): List<OciImageName> {
        for (customMapping in customMappings) {
            customMapping.map(componentCapabilities, allCapabilities)?.let {
                return it
            }
        }
        return componentCapabilities.map { versionedCapability ->
            val capability = versionedCapability.capability
            mapper.map(capability.group, capability.name, versionedCapability.version)
        }
    }
}

fun OciImageNameCapabilityMapping.createCapabilityMapper() =
    OciImageNameCapabilityMapper(createMapper(), customMappings.get())