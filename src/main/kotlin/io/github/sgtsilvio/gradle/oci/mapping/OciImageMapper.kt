package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import java.util.*

private const val GROUP_PARAMETER_KEY = "group"
private const val NAME_PARAMETER_KEY = "name"
private const val VERSION_PARAMETER_KEY = "version"
private const val FEATURE_VARIANT_PARAMETER_KEY = "featureVariant"
private const val IMAGE_NAMESPACE_PARAMETER_KEY = "imageNamespace"
internal val GROUP_PARAMETER_NAME_SPEC = ParameterNameSpec(GROUP_PARAMETER_KEY, null)
internal val NAME_PARAMETER_NAME_SPEC = ParameterNameSpec(NAME_PARAMETER_KEY, null)
internal val VERSION_PARAMETER_NAME_SPEC = ParameterNameSpec(VERSION_PARAMETER_KEY, null)
internal val FEATURE_VARIANT_PARAMETER_NAME_SPEC = ParameterNameSpec(FEATURE_VARIANT_PARAMETER_KEY, "")
private val IMAGE_NAMESPACE_PARAMETER_NAME_SPEC = ParameterNameSpec(IMAGE_NAMESPACE_PARAMETER_KEY, null)
private val DEFAULT_CAPABILITY = Triple(
    GROUP_PARAMETER_NAME_SPEC,
    NAME_PARAMETER_NAME_SPEC + FEATURE_VARIANT_PARAMETER_NAME_SPEC.prefix("-"),
    VERSION_PARAMETER_NAME_SPEC,
)
private val DEFAULT_IMAGE_NAME = IMAGE_NAMESPACE_PARAMETER_NAME_SPEC + NAME_PARAMETER_NAME_SPEC
private val DEFAULT_IMAGE_TAG = VERSION_PARAMETER_NAME_SPEC + FEATURE_VARIANT_PARAMETER_NAME_SPEC.prefix("-")

internal fun OciImageMappingData.map(componentId: VersionedCoordinates): MappedComponent {
    val componentSpec = componentMappings[componentId]
        ?: moduleMappings[componentId.coordinates]
        ?: groupMappings[componentId.group]
        ?: return defaultMappedComponent(componentId)
    val parameters = HashMap<String, String>().apply {
        put(GROUP_PARAMETER_KEY, componentId.group)
        put(NAME_PARAMETER_KEY, componentId.name)
        put(VERSION_PARAMETER_KEY, componentId.version)
        put(IMAGE_NAMESPACE_PARAMETER_KEY, defaultMappedImageNamespace(componentId.group))
    }
    val defaultFeatureVariantImageName = componentSpec.mainVariant.imageName ?: DEFAULT_IMAGE_NAME
    val defaultFeatureVariantImageTag = componentSpec.mainVariant.imageTag ?: DEFAULT_IMAGE_TAG
    return MappedComponent(
        componentId,
        LinkedHashMap<String, MappedComponent.Variant>().apply {
            put("main", componentSpec.mainVariant.map(parameters, DEFAULT_IMAGE_NAME, DEFAULT_IMAGE_TAG))
            putAll(componentSpec.featureVariants.mapValuesTo(TreeMap()) { (featureVariantName, variantSpec) ->
                parameters[FEATURE_VARIANT_PARAMETER_KEY] = featureVariantName
                variantSpec.map(parameters, defaultFeatureVariantImageName, defaultFeatureVariantImageTag)
            })
        },
    )
}

private fun OciImageMappingData.VariantSpec.map(
    parameters: Map<String, String>,
    defaultImageName: NameSpec,
    defaultImageTag: NameSpec,
) = MappedComponent.Variant(
    capabilities.ifEmpty { listOf(DEFAULT_CAPABILITY) }.mapTo(TreeSet()) { (group, name, version) ->
        VersionedCoordinates(
            group.generateName(parameters),
            name.generateName(parameters),
            version.generateName(parameters),
        )
    },
    OciImageReference(
        (imageName ?: defaultImageName).generateName(parameters),
        (imageTag ?: defaultImageTag).generateName(parameters),
    ),
)

private fun defaultMappedComponent(componentId: VersionedCoordinates) = MappedComponent(
    componentId,
    mapOf(
        "main" to MappedComponent.Variant(
            sortedSetOf(componentId),
            OciImageReference(
                defaultMappedImageNamespace(componentId.group) + componentId.name,
                componentId.version,
            ),
        )
    ),
)

internal fun defaultMappedImageNamespace(group: String) = when (val tldEndIndex = group.indexOf('.')) {
    -1 -> if (group.isEmpty()) "" else "$group/"
    else -> group.substring(tldEndIndex + 1).replace('.', '/') + '/'
}
