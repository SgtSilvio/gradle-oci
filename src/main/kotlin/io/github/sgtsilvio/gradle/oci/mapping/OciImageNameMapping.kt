package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
interface OciImageNameMapping {

    fun mapGroup(group: String, action: Action<ComponentSpec>)

    fun mapModule(group: String, name: String, action: Action<ComponentSpec>)

    fun mapComponent(group: String, name: String, version: String, action: Action<ComponentSpec>)

    fun from(imageMapping: OciImageNameMapping)

    interface VariantSpec {
        val group: NameSpec
        val name: NameSpec
        val version: NameSpec
        val featureVariant: NameSpec
//        val defaultImageName: NameSpec // TODO remove?
//        val defaultTagName: NameSpec // TODO remove?

        fun withCapabilities(action: Action<CapabilitySpecs>)

        fun toImage(imageName: String): ImageNameSpec

        fun toImage(imageName: NameSpec): ImageNameSpec

        fun nameSpec(string: String): NameSpec

        interface CapabilitySpecs {
            fun add(group: NameSpec, name: NameSpec, version: NameSpec)
        }

        interface ImageNameSpec {
            fun withTag(tagName: String)

            fun withTag(tagName: NameSpec)
        }
    }

    interface ComponentSpec : VariantSpec {
        fun featureVariant(name: String, action: Action<FeatureVariantSpec>)
    }

    interface FeatureVariantSpec : VariantSpec
}

abstract class OciImageNameMappingImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
) : OciImageNameMapping {
    private val groupMappings = objectFactory.mapProperty<String, ComponentSpec>()
    private val moduleMappings = objectFactory.mapProperty<Coordinates, ComponentSpec>()
    private val componentMappings = objectFactory.mapProperty<VersionedCoordinates, ComponentSpec>()

    @get:Input
    protected val json get() = getData().encodeToJsonString()

    final override fun mapGroup(group: String, action: Action<OciImageNameMapping.ComponentSpec>) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        groupMappings.put(group, componentSpec)
    }

    final override fun mapModule(group: String, name: String, action: Action<OciImageNameMapping.ComponentSpec>) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        moduleMappings.put(Coordinates(group, name), componentSpec)
    }

    final override fun mapComponent(
        group: String,
        name: String,
        version: String,
        action: Action<OciImageNameMapping.ComponentSpec>,
    ) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        componentMappings.put(VersionedCoordinates(Coordinates(group, name), version), componentSpec)
    }

    final override fun from(imageMapping: OciImageNameMapping) {
        require(imageMapping is OciImageNameMappingImpl)
        groupMappings.putAll(imageMapping.groupMappings)
        moduleMappings.putAll(imageMapping.moduleMappings)
        componentMappings.putAll(imageMapping.componentMappings)
    }

    @Internal
    fun getData() = OciImageNameMappingData(
        groupMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
        moduleMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
        componentMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
    )

    abstract class VariantSpec : OciImageNameMapping.VariantSpec, OciImageNameMapping.VariantSpec.CapabilitySpecs,
        OciImageNameMapping.VariantSpec.ImageNameSpec {
        final override val group get() = GROUP_PARAMETER_NAME_SPEC
        final override val name get() = NAME_PARAMETER_NAME_SPEC
        final override val version get() = VERSION_PARAMETER_NAME_SPEC
        final override val featureVariant get() = FEATURE_VARIANT_PARAMETER_NAME_SPEC
        protected val capabilities = mutableListOf<Triple<NameSpec, NameSpec, NameSpec>>()
        protected var imageName: NameSpec? = null
        protected var tagName: NameSpec? = null

        final override fun withCapabilities(action: Action<OciImageNameMapping.VariantSpec.CapabilitySpecs>) =
            action.execute(this)

        override fun add(group: NameSpec, name: NameSpec, version: NameSpec) {
            capabilities += Triple(group, name, version)
        }

        final override fun toImage(imageName: String) = toImage(StringNameSpec(imageName))

        final override fun toImage(imageName: NameSpec): VariantSpec {
            this.imageName = imageName
            return this
        }

        final override fun withTag(tagName: String) = withTag(StringNameSpec(tagName))

        final override fun withTag(tagName: NameSpec) {
            this.tagName = tagName
        }

        final override fun nameSpec(string: String) = StringNameSpec(string)
    }

    abstract class ComponentSpec @Inject constructor(
        private val objectFactory: ObjectFactory,
    ) : VariantSpec(), OciImageNameMapping.ComponentSpec {
        private val featureVariants = mutableMapOf<String, FeatureVariantSpec>()

        override fun featureVariant(name: String, action: Action<OciImageNameMapping.FeatureVariantSpec>) {
            require(name != "main") { "featureVariant name must not be 'main'" }
            val featureVariantSpec = objectFactory.newInstance<FeatureVariantSpec>() // TODO if already exists? => overwrite
            action.execute(featureVariantSpec)
            featureVariants[name] = featureVariantSpec
        }

        fun getData() = OciImageNameMappingData.ComponentSpec(
            OciImageNameMappingData.VariantSpec(capabilities, imageName, tagName),
            featureVariants.mapValuesTo(TreeMap()) { it.value.getData() },
        )
    }

    abstract class FeatureVariantSpec : VariantSpec(), OciImageNameMapping.FeatureVariantSpec {

        fun getData() = OciImageNameMappingData.VariantSpec(capabilities, imageName, tagName)
    }
}
