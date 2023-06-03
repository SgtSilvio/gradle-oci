package io.github.sgtsilvio.gradle.oci.mapping

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.VersionedCoordinates
import java.util.*

private const val GROUP_PARAMETER_KEY = "group"
private const val NAME_PARAMETER_KEY = "name"
private const val VERSION_PARAMETER_KEY = "version"
private const val FEATURE_VARIANT_PARAMETER_KEY = "featureVariant"
private const val IMAGE_NAMESPACE_PARAMETER_KEY = "imageNamespace"
val GROUP_PARAMETER_NAME_SPEC = ParameterNameSpec(GROUP_PARAMETER_KEY, null)
val NAME_PARAMETER_NAME_SPEC = ParameterNameSpec(NAME_PARAMETER_KEY, null)
val VERSION_PARAMETER_NAME_SPEC = ParameterNameSpec(VERSION_PARAMETER_KEY, null)
val FEATURE_VARIANT_PARAMETER_NAME_SPEC = ParameterNameSpec(FEATURE_VARIANT_PARAMETER_KEY, "")
val IMAGE_NAMESPACE_PARAMETER_NAME_SPEC = ParameterNameSpec(IMAGE_NAMESPACE_PARAMETER_KEY, null)
val DEFAULT_CAPABILITY = Triple(
    GROUP_PARAMETER_NAME_SPEC,
    NAME_PARAMETER_NAME_SPEC + FEATURE_VARIANT_PARAMETER_NAME_SPEC.prefix("-"),
    VERSION_PARAMETER_NAME_SPEC,
)
val DEFAULT_IMAGE_NAME = IMAGE_NAMESPACE_PARAMETER_NAME_SPEC + NAME_PARAMETER_NAME_SPEC
val DEFAULT_TAG_NAME = VERSION_PARAMETER_NAME_SPEC + FEATURE_VARIANT_PARAMETER_NAME_SPEC.prefix("-")

fun OciImageNameMappingData.map(componentId: VersionedCoordinates): MappedComponent {
    val componentSpec = componentMappings[componentId]
        ?: moduleMappings[componentId.coordinates]
        ?: groupMappings[componentId.coordinates.group]
        ?: return defaultMappedComponent(componentId)
    val parameters = HashMap<String, String>().apply {
        put(GROUP_PARAMETER_KEY, componentId.coordinates.group)
        put(NAME_PARAMETER_KEY, componentId.coordinates.name)
        put(VERSION_PARAMETER_KEY, componentId.version)
        put(IMAGE_NAMESPACE_PARAMETER_KEY, defaultMappedImageNamespace(componentId.coordinates.group))
    }
    val defaultFeatureVariantImageName = componentSpec.mainVariant.imageName ?: DEFAULT_IMAGE_NAME
    val defaultFeatureVariantTagName = componentSpec.mainVariant.tagName ?: DEFAULT_TAG_NAME
    return MappedComponent(
        componentId,
        LinkedHashMap<String, MappedComponent.Variant>().apply {
            put("main", componentSpec.mainVariant.map(parameters, DEFAULT_IMAGE_NAME, DEFAULT_TAG_NAME))
            putAll(componentSpec.featureVariants.mapValuesTo(TreeMap()) { (featureVariantName, variantSpec) ->
                parameters[FEATURE_VARIANT_PARAMETER_KEY] = featureVariantName
                variantSpec.map(parameters, defaultFeatureVariantImageName, defaultFeatureVariantTagName)
            })
        },
    )
}

private fun OciImageNameMappingData.VariantSpec.map(
    parameters: Map<String, String>,
    defaultImageName: NameSpec,
    defaultTagName: NameSpec,
) = MappedComponent.Variant(
    capabilities.ifEmpty { listOf(DEFAULT_CAPABILITY) }.mapTo(TreeSet()) { (group, name, version) ->
        VersionedCoordinates(
            Coordinates(group.generateName(parameters), name.generateName(parameters)),
            version.generateName(parameters)
        )
    },
    (imageName ?: defaultImageName).generateName(parameters),
    (tagName ?: defaultTagName).generateName(parameters),
)

private fun defaultMappedComponent(componentId: VersionedCoordinates) = MappedComponent(
    componentId,
    mapOf(
        "main" to MappedComponent.Variant(
            sortedSetOf(componentId),
            defaultMappedImageNamespace(componentId.coordinates.group) + componentId.coordinates.name,
            componentId.version,
        )
    ),
)

fun defaultMappedImageNamespace(group: String) = when (val tldEndIndex = group.indexOf('.')) {
    -1 -> if (group.isEmpty()) "" else "$group/"
    else -> group.substring(tldEndIndex + 1).replace('.', '/') + '/'
}

fun OciImageNameMappingData.map(
    componentId: VersionedCoordinates,
    capabilities: SortedSet<VersionedCoordinates>,
): MappedComponent.Variant? = map(componentId).variants.values.find { it.capabilities == capabilities }
