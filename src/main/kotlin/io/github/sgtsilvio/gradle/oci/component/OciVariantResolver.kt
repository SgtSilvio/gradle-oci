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
        resolve(rootComponentResult, rootComponentResult.variants.first()).dependencies

    private fun resolve(
        componentResult: ResolvedComponentResult,
        variantResult: ResolvedVariantResult,
    ): OciVariantState {
        if (variantResult in states) {
            return states[variantResult] ?: throw IllegalStateException("cycle in dependencies graph")
        }
        states[variantResult] = null
        val dependencies = componentResult.getDependenciesForVariant(variantResult).mapNotNull { dependencyResult ->
            if (dependencyResult !is ResolvedDependencyResult) {
                throw IllegalStateException("unresolved dependency $dependencyResult")
            }
            if (dependencyResult.isConstraint) {
                null
            } else {
                resolve(dependencyResult.selected, dependencyResult.resolvedVariant)
            }
        }
        val platform = variantResult.platform
        val platformSet: PlatformSet
        if (platform == MULTIPLE_PLATFORMS_ATTRIBUTE_VALUE) {
            platformSet = PlatformSet(false)
            for (dependency in dependencies) {
                platformSet.union(dependency.platformSet)
            }
//            OciVariantState.MultiplePlatforms(variantResult, platformSet, )
        } else {
            platformSet = if (platform == UNIVERSAL_PLATFORM_ATTRIBUTE_VALUE) {
                PlatformSet(true)
            } else {
                PlatformSet(platform.toPlatform())
            }
            for (dependency in dependencies) {
                platformSet.intersect(dependency.platformSet)
            }
//            OciVariantState.SinglePlatformOrUniversal(variantResult, platformSet, dependencies)
        }
        val state = OciVariantState(variantResult, platformSet, dependencies)
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
//    if (multiple) {
//        dependencies[platform]?.collectVariantResultsForPlatform(platform, result)
//            ?: throw IllegalStateException("unresolved dependency for platform $platform") // TODO message
//    } else {
//        if (variantResult !in result) {
//            for (dependency in dependencies) {
//                dependency.collectVariantResultsForPlatform(platform, result)
//            }
//            result += variantResult
//        }
//    }
    TODO()
}

class OciVariantState(
    val variantResult: ResolvedVariantResult,
    val platformSet: PlatformSet,
    val dependencies: List<OciVariantState>,
)

//sealed class OciVariantState(
//    val variantResult: ResolvedVariantResult,
//    val platformSet: PlatformSet,
//) {
//
//    class MultiplePlatforms(
//        variantResult: ResolvedVariantResult,
//        platformSet: PlatformSet,
//        val platformToVariant: Map<Platform, OciVariantState>,
//    ) : OciVariantState(variantResult, platformSet)
//
//    class SinglePlatformOrUniversal(
//        variantResult: ResolvedVariantResult,
//        platformSet: PlatformSet,
//        val dependencies: List<OciVariantState>,
//    ) : OciVariantState(variantResult, platformSet)
//}

val ResolvedVariantResult.platform: String
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
