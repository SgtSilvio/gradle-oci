package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.DefaultTask
import org.gradle.api.NonExtensible
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance

/**
 * @author Silvio Giebl
 */
@NonExtensible
interface OciImagesInput {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val files: ConfigurableFileCollection

    @get:Input
    val rootCapabilities: SetProperty<Coordinates>

    fun from(configuration: Configuration) {
        files.from(configuration)
        val configurationName = configuration.name
        rootCapabilities.set(configuration.incoming.resolutionResult.rootComponent.map { rootComponent ->
            val rootVariant = rootComponent.variants.find { it.displayName == configurationName }!!
            rootComponent.getDependenciesForVariant(rootVariant)
                .filter { !it.isConstraint }
                .filterIsInstance<ResolvedDependencyResult>() // ignore unresolved, rely on resolution of files
//                .filter { it.resolvedVariant.capabilities.first().group != "io.github.sgtsilvio.gradle.oci.tag" }
                .map { dependencyResult ->
                    val capability = dependencyResult.resolvedVariant.capabilities.first()
                    Coordinates(capability.group, capability.name)
                }
        })
    }
}

abstract class OciImagesInputTask : DefaultTask() {

    @get:Nested
    val imagesList = project.objects.listProperty<OciImagesInput>()

    fun from(configurationsProvider: Provider<List<Configuration>>) =
        imagesList.addAll(configurationsProvider.map { configurations ->
            configurations.map { configuration ->
                project.objects.newInstance<OciImagesInput>().apply { from(configuration) }
            }
        })
}
