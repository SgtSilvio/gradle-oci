package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import java.util.*

fun OciImageNameCapabilityMappingData.map(
    componentId: VersionedCoordinates,
    componentCapabilities: SortedSet<VersionedCoordinates>,
    allCapabilities: Set<VersionedCoordinates>,
): OciImageId? {
    for (customMapping in customMappings) {
        customMapping.map(componentId, componentCapabilities, allCapabilities)?.let {
            return it
        }
    }
    val mappedVariant = delegate.map(componentId, componentCapabilities) ?: return null
    return OciImageId(mappedVariant.imageName, mappedVariant.tagName)
}
