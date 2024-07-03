package io.github.sgtsilvio.gradle.oci.component

import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.*

/**
 * @author Silvio Giebl
 */
class ArtifactViewComponentFilter(
    private val rootComponentResultProvider: Provider<ResolvedComponentResult>,
    private val variantImagesProvider: Provider<List<Map<Platform, List<ResolvedVariantResult>>>>,
) : Spec<ComponentIdentifier> {

    private class State(
        val componentIdToVariantResults: Map<ComponentIdentifier, Iterator<ResolvedVariantResult>>,
        val selectedVariantResults: Set<ResolvedVariantResult?>,
    )

    private var state: State? = null

    private fun getState(): State {
        return state ?: run {
            val rootComponentResult = rootComponentResultProvider.get()
            val variantImages = variantImagesProvider.get()
            val componentIdToVariantResults =
                rootComponentResult.allComponents.associateByTo(HashMap(), { it.id }) { it.variants.iterator() }
            componentIdToVariantResults[rootComponentResult.id]!!.next()
            val selectedVariantResults =
                variantImages.flatMapTo(HashSet<ResolvedVariantResult?>()) { it.values.flatten() }
            val newState = State(componentIdToVariantResults, selectedVariantResults)
            state = newState
            newState
        }
    }

    override fun isSatisfiedBy(componentId: ComponentIdentifier?): Boolean {
        val state = getState()
        return state.componentIdToVariantResults[componentId]?.next() in state.selectedVariantResults
    }
}

val ResolvedComponentResult.allComponents: HashSet<ResolvedComponentResult>
    get() {
        val visitedComponentResults = HashSet<ResolvedComponentResult>()
        val componentResultsToVisit = LinkedList<ResolvedComponentResult>()
        visitedComponentResults += this
        componentResultsToVisit += this
        while (true) {
            val componentResult = componentResultsToVisit.poll() ?: break
            for (dependency in componentResult.dependencies) {
                if (dependency !is ResolvedDependencyResult) continue
                if (visitedComponentResults.add(dependency.selected)) {
                    componentResultsToVisit += dependency.selected
                }
            }
        }
        return visitedComponentResults
    }
