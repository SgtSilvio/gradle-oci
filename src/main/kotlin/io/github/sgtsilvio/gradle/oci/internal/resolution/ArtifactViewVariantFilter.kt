package io.github.sgtsilvio.gradle.oci.internal.resolution

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
internal class ArtifactViewVariantFilter(
    private val rootComponentResult: Provider<ResolvedComponentResult>,
    private val selectedVariantResults: Provider<Set<ResolvedVariantResult>>,
) : Spec<ComponentIdentifier> {

    private class State(
        val componentIdToVariantResults: Map<ComponentIdentifier, CyclicIterator<ResolvedVariantResult>>,
        val selectedVariantResults: Set<ResolvedVariantResult>,
    )

    private var state: State? = null

    private fun getState(): State {
        return state ?: run {
            val rootComponentResult = rootComponentResult.get()
            val selectedVariantResults = selectedVariantResults.get()
            val componentIdToVariantResults =
                rootComponentResult.allComponents.associateByTo(HashMap(), { it.id }) { CyclicIterator(it.variants) }
            componentIdToVariantResults[rootComponentResult.id] = CyclicIterator(rootComponentResult.variants.drop(1))
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

private val ResolvedComponentResult.allComponents: HashSet<ResolvedComponentResult>
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

private class CyclicIterator<E>(private val list: List<E>) {
    private var index = 0

    fun next(): E? {
        if (list.isEmpty()) {
            return null
        }
        if (index > list.lastIndex) {
            index = 0
        }
        return list[index++]
    }
}
