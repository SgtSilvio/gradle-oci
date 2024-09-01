package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.OciImagesTask
import io.github.sgtsilvio.gradle.oci.attributes.*
import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependencies
import io.github.sgtsilvio.gradle.oci.dsl.OciImageDependenciesWithScopes
import io.github.sgtsilvio.gradle.oci.internal.resolution.resolveOciImageInputs
import io.github.sgtsilvio.gradle.oci.internal.string.concatCamelCase
import io.github.sgtsilvio.gradle.oci.platform.PlatformSelector
import org.gradle.api.Action
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyConstraintHandler
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
internal abstract class OciImageDependenciesImpl @Inject constructor(
    private val name: String,
    objectFactory: ObjectFactory,
    configurationContainer: ConfigurationContainer,
) : OciImageDependencies {

    final override fun getName() = name

    final override val runtime = objectFactory.newInstance<ReferencableOciImageDependencyCollectorImpl>()

    private val configuration: Configuration = configurationContainer.create(name + "OciImages").apply {
        description = "OCI image dependencies '$name'"
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.apply {
            attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(DISTRIBUTION_CATEGORY))
            attribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
            attribute(Bundling.BUNDLING_ATTRIBUTE, objectFactory.named(Bundling.EXTERNAL))
            attribute(PLATFORM_ATTRIBUTE, MULTI_PLATFORM_ATTRIBUTE_VALUE)
        }
        dependencies.addAllLater(runtime.dependencies)
        dependencyConstraints.addAllLater(runtime.dependencyConstraints)
    }

    final override fun resolve(platformSelector: Provider<PlatformSelector>) =
        configuration.incoming.resolveOciImageInputs(platformSelector)
}

internal abstract class OciImageDependenciesWithScopesImpl @Inject constructor(
    private val name: String,
    private val oci: OciExtension,
    private val objectFactory: ObjectFactory,
    dependencyConstraintHandler: DependencyConstraintHandler,
) : DependencyConstraintFactoriesImpl(dependencyConstraintHandler), OciImageDependenciesWithScopes {

    final override fun getName() = name

    // linked because it will be iterated
    private val scopes = LinkedHashMap<String, OciImageDependencies>()

    final override val runtime = scope("").runtime

    final override fun resolve(platformSelector: Provider<PlatformSelector>): Provider<List<OciImagesTask.ImageInput>> {
        val resolved = objectFactory.listProperty<OciImagesTask.ImageInput>()
        for (scope in scopes.values) {
            resolved.addAll(scope.resolve(platformSelector))
        }
        return resolved
    }

    final override fun scope(name: String) = scopes.getOrPut(name) {
        oci.imageDependencies.create(this.name.concatCamelCase(name))
    }

    final override fun scope(name: String, action: Action<in OciImageDependencies>) = action.execute(scope(name))
}
