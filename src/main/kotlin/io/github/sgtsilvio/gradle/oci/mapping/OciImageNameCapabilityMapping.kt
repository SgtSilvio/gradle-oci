package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.kotlin.dsl.listProperty
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
interface OciImageNameCapabilityMapping : OciImageNameMapping {

    @get:Nested
    val customMappings: ListProperty<CustomOciImageNameCapabilityMapper>
}

fun interface CustomOciImageNameCapabilityMapper {
    fun map(
        componentId: VersionedCoordinates,
        componentCapabilities: Set<VersionedCoordinates>,
        allCapabilities: Set<VersionedCoordinates>,
    ): OciImageId?
}

abstract class OciImageNameCapabilityMappingImpl @Inject constructor(
    objectFactory: ObjectFactory,
) : OciImageNameMappingImpl(objectFactory), OciImageNameCapabilityMapping {
    final override val customMappings = objectFactory.listProperty<CustomOciImageNameCapabilityMapper>()

    @Internal
    fun getData2() = OciImageNameCapabilityMappingData(getData(), customMappings.get())
}

class OciImageNameCapabilityMappingData(
    val delegate: OciImageNameMappingData,
    val customMappings: List<CustomOciImageNameCapabilityMapper>,
)
