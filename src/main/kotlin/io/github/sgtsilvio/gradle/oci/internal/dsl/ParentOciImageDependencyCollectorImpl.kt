package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.ParentOciImageDependencyCollector
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyFactory
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class ParentOciImageDependencyCollectorImpl @Inject constructor(
    objectFactory: ObjectFactory,
    dependencyFactory: DependencyFactory,
    dependencyHandler: DependencyHandler,
) : OciImageDependencyCollectorBaseImpl<Unit>(objectFactory, dependencyFactory, dependencyHandler),
    ParentOciImageDependencyCollector {

    final override fun addInternal(dependency: ModuleDependency) = dependencies.add(dependency)

    final override fun addInternal(dependencyProvider: Provider<out ModuleDependency>) =
        dependencies.add(dependencyProvider)
}
