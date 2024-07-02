package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.OciImagesInput2
import io.github.sgtsilvio.gradle.oci.attributes.MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.attributes.PLATFORM_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.github.sgtsilvio.gradle.oci.platform.toPlatform
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.specs.Spec

class OciVariantResolver {
    private val states = HashMap<ResolvedVariantResult, OciVariantState?>()

    fun resolve(rootComponentResult: ResolvedComponentResult) =
        rootComponentResult.getDependenciesForVariant(rootComponentResult.variants.first()).mapNotNull { dependencyResult ->
            if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
                null
            } else {
                resolve(dependencyResult.selected, dependencyResult.resolvedVariant)
            }
        }

    private fun resolve(
        componentResult: ResolvedComponentResult,
        variantResult: ResolvedVariantResult,
    ): OciVariantState {
        if (variantResult in states) {
            return states[variantResult] ?: throw IllegalStateException("cycle in dependencies graph")
        }
        states[variantResult] = null
        val dependencies = componentResult.getDependenciesForVariant(variantResult).mapNotNull { dependencyResult ->
            if ((dependencyResult !is ResolvedDependencyResult) || dependencyResult.isConstraint) {
                null
            } else {
                resolve(dependencyResult.selected, dependencyResult.resolvedVariant)
            }
        }
        val state = when (val platformOrUniversalOrMultiple = variantResult.platformOrUniversalOrMultiple) {
            MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE -> {
                val platformToDependency = HashMap<Platform, OciVariantState.SinglePlatform>()
                for (dependency in dependencies) {
                    if (dependency !is OciVariantState.SinglePlatform) {
                        throw IllegalStateException() // TODO
                    }
                    val platform = dependency.platformSet.singleOrNull()
                    if (platform != null) {
                        if (platform in platformToDependency) {
                            throw IllegalStateException() // TODO
                        }
                        platformToDependency[platform] = dependency
                    }
                }
                OciVariantState.MultiplePlatforms(variantResult, platformToDependency)
            }

            UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE -> {
                val platformSet = PlatformSet(true)
                for (dependency in dependencies) {
                    platformSet.intersect(dependency.platformSet)
                }
                OciVariantState.Universal(variantResult, platformSet, dependencies)
            }

            else -> {
                val platformSet = PlatformSet(platformOrUniversalOrMultiple.toPlatform())
                for (dependency in dependencies) {
                    platformSet.intersect(dependency.platformSet)
                }
                OciVariantState.SinglePlatform(variantResult, platformSet, dependencies)
            }
        }
        states[variantResult] = state
        return state
    }
}

fun createArtifactViewFilter(): Spec<ComponentIdentifier> {
    return Spec { componentIdentifier ->
        TODO()
    }
}

fun createImagesInput(rootVariantStates: List<OciVariantState>): OciImagesInput2 {
    val x = rootVariantStates.map { rootVariantState ->
        rootVariantState.platformSet.associateWith { platform ->
            rootVariantState.collectVariantResultsForPlatform(platform)
        }
    }
    TODO()
}

fun OciVariantState.collectVariantResultsForPlatform(platform: Platform): LinkedHashSet<ResolvedVariantResult> {
    val result = LinkedHashSet<ResolvedVariantResult>()
    collectVariantResultsForPlatform(platform, result)
    return result
}

fun OciVariantState.collectVariantResultsForPlatform(platform: Platform, result: LinkedHashSet<ResolvedVariantResult>) {
    if (variantResult !in result) {
        when (this) {
            is OciVariantState.MultiplePlatforms -> {
                platformToDependency[platform]?.collectVariantResultsForPlatform(platform, result)
                    ?: throw IllegalStateException("unresolved dependency for platform $platform") // TODO message
            }
            is OciVariantState.SinglePlatformOrUniversal -> {
                for (dependency in dependencies) {
                    dependency.collectVariantResultsForPlatform(platform, result)
                }
            }
        }
        result += variantResult
    }
}

sealed class OciVariantState(
    val variantResult: ResolvedVariantResult,
    val platformSet: PlatformSet,
) {

    class MultiplePlatforms(
        variantResult: ResolvedVariantResult,
        val platformToDependency: Map<Platform, SinglePlatform>,
    ) : OciVariantState(variantResult, PlatformSet(platformToDependency.keys))

    sealed class SinglePlatformOrUniversal(
        variantResult: ResolvedVariantResult,
        platformSet: PlatformSet,
        val dependencies: List<OciVariantState>,
    ) : OciVariantState(variantResult, platformSet)

    class SinglePlatform(
        variantResult: ResolvedVariantResult,
        platformSet: PlatformSet,
        dependencies: List<OciVariantState>,
    ) : SinglePlatformOrUniversal(variantResult, platformSet, dependencies)

    class Universal(
        variantResult: ResolvedVariantResult,
        platformSet: PlatformSet,
        dependencies: List<OciVariantState>,
    ) : SinglePlatformOrUniversal(variantResult, platformSet, dependencies)
}

val ResolvedVariantResult.platformOrUniversalOrMultiple: String
    get() {
        val platformAttribute = attributes.getAttribute(PLATFORM_ATTRIBUTE)
        if (platformAttribute != null) {
            return platformAttribute
        }
        return capabilities.first().name.substringAfterLast('@')
    }





//@JvmInline
//value class Union private constructor(val value: Any) {
//    constructor(i: Int) : this(i as Any)
//    constructor(s: String) : this(s as Any)
//
//    inline fun <R> match(
//        isInt: (Int) -> R,
//        isString: (String) -> R,
//    ) = when (value) {
//        is Int -> isInt(value)
//        is String -> isString(value)
//        else -> throw IllegalStateException()
//    }
//}
//
//fun main() {
////    val union = Union("aso")
//    val union = Union(123)
//
//    union.match(
//        isInt = { println("int $it") },
//        isString = { println("String $it") },
//    )
//}





/*
request multiple platforms
  uni          -> uni
               -> multi (meta)
  single       -> uni
               -> multi (meta)
  multi (meta) -> single       => unique platforms

request single platform
  uni           -> uni
                -> single (meta)
  single        -> uni
                -> single (meta)
  single (meta) -> single        => platforms need to be the same


node types:
- single  platform?                     list<dependency>
- uni     set<platform> (can be empty)  list<dependency>
- multi   map<platform, dependency> (can be empty)

first round:
node types:
- OciVariantState  set<platform>  list<dependency>
 */
