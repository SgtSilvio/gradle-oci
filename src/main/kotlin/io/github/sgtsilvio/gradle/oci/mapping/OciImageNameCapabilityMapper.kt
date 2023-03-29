package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCapability

/**
 * @author Silvio Giebl
 */
class OciImageNameCapabilityMapper(
    private val mapper: OciImageNameMapper,
    private val customMappings: List<CustomOciImageNameCapabilityMapper>,
) {

    // inspired by InclusiveRepositoryContentDescriptor
    // map group to namespace, for example io.confluent -> confluentinc => mapGroup(ByRegex)
    // map group+name to namespace+name => mapModule(ByRegex)
    // map group+name+version to namespace+name+tag => mapModuleVersion(ByRegex)
    // the above map for every component capability but not for all (transitive) capabilities
    // first custom (below, tbd), then moduleVersion, then module, then group, then default
    // custom (componentCaps: Set<VersionedCapability>, allCaps: Set<VersionedCapability) -> List<ImageName>

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