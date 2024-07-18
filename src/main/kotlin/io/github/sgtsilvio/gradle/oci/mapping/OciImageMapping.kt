package io.github.sgtsilvio.gradle.oci.mapping

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
interface OciImageMapping {

    fun mapGroup(group: String, action: Action<in ComponentSpec>)

    fun mapModule(group: String, name: String, action: Action<in ComponentSpec>)

    fun mapComponent(group: String, name: String, version: String, action: Action<in ComponentSpec>)

    interface FeatureSpec {
        val group: NameSpec
        val name: NameSpec
        val version: NameSpec
        val featureName: NameSpec

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

    interface ComponentSpec : FeatureSpec {
        fun withFeature(name: String, action: Action<in FeatureSpec>)
    }
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

    fun getData() = OciImageMappingData(
        groupMappings.get().mapValues { it.value.getData() },
        moduleMappings.get().mapValues { it.value.getData() },
        componentMappings.get().mapValues { it.value.getData() },
    )

    abstract class FeatureSpec : OciImageMapping.FeatureSpec, OciImageMapping.FeatureSpec.CapabilitySpecs,
        OciImageMapping.FeatureSpec.ImageNameSpec {
        final override val group: NameSpec get() = GROUP_PARAMETER_NAME_SPEC
        final override val name: NameSpec get() = NAME_PARAMETER_NAME_SPEC
        final override val version: NameSpec get() = VERSION_PARAMETER_NAME_SPEC
        final override val featureName: NameSpec get() = FEATURE_NAME_PARAMETER_NAME_SPEC
        private val capabilities = mutableListOf<Triple<NameSpec, NameSpec, NameSpec>>()
        private var imageName: NameSpec? = null
        private var imageTag: NameSpec? = null

        final override fun withCapabilities(action: Action<in OciImageMapping.FeatureSpec.CapabilitySpecs>) =
            action.execute(this)

        final override fun add(group: NameSpec, name: NameSpec, version: NameSpec) {
            capabilities += Triple(group, name, version)
        }

        final override fun toImage(name: String) = toImage(StringNameSpec(name))

        final override fun toImage(name: NameSpec): FeatureSpec {
            imageName = name
            return this
        }

        final override fun withTag(tag: String) = withTag(StringNameSpec(tag))

        final override fun withTag(tag: NameSpec) {
            imageTag = tag
        }

        final override fun nameSpec(string: String): NameSpec = StringNameSpec(string)

        fun createFeatureSpecData() = OciImageMappingData.FeatureSpec(capabilities, imageName, imageTag)
    }

    abstract class ComponentSpec @Inject constructor(
        private val objectFactory: ObjectFactory,
    ) : FeatureSpec(), OciImageMapping.ComponentSpec {
        private val features = mutableMapOf<String, FeatureSpec>()

        final override fun withFeature(name: String, action: Action<in OciImageMapping.FeatureSpec>) {
            require(name != "main") { "feature name must not be 'main'" }
            val featureSpec = objectFactory.newInstance<FeatureSpec>()
            action.execute(featureSpec)
            features[name] = featureSpec
        }

        fun getData() = OciImageMappingData.ComponentSpec(
            createFeatureSpecData(),
            features.mapValues { it.value.createFeatureSpecData() },
        )
    }
}
