package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
interface OciImageMapping {

    fun mapGroup(group: String, action: Action<in ComponentSpec>)

    fun mapModule(group: String, name: String, action: Action<in ComponentSpec>)

    fun mapComponent(group: String, name: String, version: String, action: Action<in ComponentSpec>)

    interface VariantSpec {
        val group: NameSpec
        val name: NameSpec
        val version: NameSpec
        val featureVariant: NameSpec

        fun withCapabilities(action: Action<in CapabilitySpecs>)

        fun toImage(name: String): ImageNameSpec

        fun toImage(name: NameSpec): ImageNameSpec

        fun nameSpec(string: String): NameSpec

        interface CapabilitySpecs {
            fun add(group: NameSpec, name: NameSpec, version: NameSpec)
        }

        interface ImageNameSpec {
            fun withTag(tag: String)

            fun withTag(tag: NameSpec)
        }
    }

    interface ComponentSpec : VariantSpec {
        fun featureVariant(name: String, action: Action<in FeatureVariantSpec>)
    }

    interface FeatureVariantSpec : VariantSpec
}

internal abstract class OciImageMappingImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
) : OciImageMapping {
    private val groupMappings = objectFactory.mapProperty<String, ComponentSpec>()
    private val moduleMappings = objectFactory.mapProperty<Coordinates, ComponentSpec>()
    private val componentMappings = objectFactory.mapProperty<VersionedCoordinates, ComponentSpec>()

    final override fun mapGroup(group: String, action: Action<in OciImageMapping.ComponentSpec>) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        groupMappings.put(group, componentSpec)
    }

    final override fun mapModule(group: String, name: String, action: Action<in OciImageMapping.ComponentSpec>) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        moduleMappings.put(Coordinates(group, name), componentSpec)
    }

    final override fun mapComponent(
        group: String,
        name: String,
        version: String,
        action: Action<in OciImageMapping.ComponentSpec>,
    ) {
        val componentSpec = objectFactory.newInstance<ComponentSpec>()
        action.execute(componentSpec)
        componentMappings.put(VersionedCoordinates(group, name, version), componentSpec)
    }

    internal fun getData() = OciImageMappingData(
        groupMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
        moduleMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
        componentMappings.get().mapValuesTo(TreeMap()) { it.value.getData() },
    )

    abstract class VariantSpec : OciImageMapping.VariantSpec, OciImageMapping.VariantSpec.CapabilitySpecs,
        OciImageMapping.VariantSpec.ImageNameSpec {
        final override val group: NameSpec get() = GROUP_PARAMETER_NAME_SPEC
        final override val name: NameSpec get() = NAME_PARAMETER_NAME_SPEC
        final override val version: NameSpec get() = VERSION_PARAMETER_NAME_SPEC
        final override val featureVariant: NameSpec get() = FEATURE_VARIANT_PARAMETER_NAME_SPEC
        protected val capabilities = mutableListOf<Triple<NameSpec, NameSpec, NameSpec>>()
        protected var imageName: NameSpec? = null
        protected var imageTag: NameSpec? = null

        final override fun withCapabilities(action: Action<in OciImageMapping.VariantSpec.CapabilitySpecs>) =
            action.execute(this)

        final override fun add(group: NameSpec, name: NameSpec, version: NameSpec) {
            capabilities += Triple(group, name, version)
        }

        final override fun toImage(name: String) = toImage(StringNameSpec(name))

        final override fun toImage(name: NameSpec): VariantSpec {
            imageName = name
            return this
        }

        final override fun withTag(tag: String) = withTag(StringNameSpec(tag))

        final override fun withTag(tag: NameSpec) {
            imageTag = tag
        }

        final override fun nameSpec(string: String): NameSpec = StringNameSpec(string)
    }

    abstract class ComponentSpec @Inject constructor(
        private val objectFactory: ObjectFactory,
    ) : VariantSpec(), OciImageMapping.ComponentSpec {
        private val featureVariants = mutableMapOf<String, FeatureVariantSpec>()

        final override fun featureVariant(name: String, action: Action<in OciImageMapping.FeatureVariantSpec>) {
            require(name != "main") { "featureVariant name must not be 'main'" }
            val featureVariantSpec = objectFactory.newInstance<FeatureVariantSpec>()
            action.execute(featureVariantSpec)
            featureVariants[name] = featureVariantSpec
        }

        internal fun getData() = OciImageMappingData.ComponentSpec(
            OciImageMappingData.VariantSpec(capabilities, imageName, imageTag),
            featureVariants.mapValuesTo(TreeMap()) { it.value.getData() },
        )
    }

    abstract class FeatureVariantSpec : VariantSpec(), OciImageMapping.FeatureVariantSpec {

        internal fun getData() = OciImageMappingData.VariantSpec(capabilities, imageName, imageTag)
    }
}
