package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible
import java.io.Serializable

/**
 * @author Silvio Giebl
 */
interface OciTaggableImageDependencies : OciImageDependencies {

    interface Reference : Serializable {
        val name: String?
        val tag: String?
    }

    interface ReferenceSpec {
        val reference: Provider<Reference>
    }

    interface NamedReferenceSpec : ReferenceSpec {
        fun tagged(tag: String): ReferenceSpec
        fun tagged(tagProvider: Provider<String>): ReferenceSpec
    }

    val rootCapabilities: Provider<Map<Coordinates, Set<Reference>>>

    // add tagged dependency

    fun add(dependency: ModuleDependency, referenceSpec: ReferenceSpec)

    fun <D : ModuleDependency> add(dependency: D, referenceSpec: ReferenceSpec, action: Action<in D>)

    fun add(dependencyProvider: Provider<out ModuleDependency>, referenceSpec: ReferenceSpec)

    fun <D : ModuleDependency> add(
        dependencyProvider: Provider<out D>,
        referenceSpec: ReferenceSpec,
        action: Action<in D>,
    )

    // add tagged dependency converted from a different notation

    fun add(dependencyNotation: CharSequence, referenceSpec: ReferenceSpec)

    fun add(dependencyNotation: CharSequence, referenceSpec: ReferenceSpec, action: Action<in ExternalModuleDependency>)

    fun add(project: Project, referenceSpec: ReferenceSpec)

    fun add(project: Project, referenceSpec: ReferenceSpec, action: Action<in ProjectDependency>)

    fun add(dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>, referenceSpec: ReferenceSpec)

    fun add(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        referenceSpec: ReferenceSpec,
        action: Action<in ExternalModuleDependency>,
    )

    // dsl syntactic sugar for adding tagged dependency

    operator fun invoke(dependency: ModuleDependency, referenceSpec: ReferenceSpec) = add(dependency, referenceSpec)

    operator fun <D : ModuleDependency> invoke(dependency: D, referenceSpec: ReferenceSpec, action: Action<in D>) =
        add(dependency, referenceSpec, action)

    operator fun invoke(dependencyProvider: Provider<out ModuleDependency>, referenceSpec: ReferenceSpec) =
        add(dependencyProvider, referenceSpec)

    operator fun <D : ModuleDependency> invoke(
        dependencyProvider: Provider<out D>,
        referenceSpec: ReferenceSpec,
        action: Action<in D>,
    ) = add(dependencyProvider, referenceSpec, action)

    // dsl syntactic sugar for adding tagged dependency converted from a different notation

    operator fun invoke(dependencyNotation: CharSequence, referenceSpec: ReferenceSpec) =
        add(dependencyNotation, referenceSpec)

    operator fun invoke(
        dependencyNotation: CharSequence,
        referenceSpec: ReferenceSpec,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyNotation, referenceSpec, action)

    operator fun invoke(project: Project, referenceSpec: ReferenceSpec) = add(project, referenceSpec)

    operator fun invoke(project: Project, referenceSpec: ReferenceSpec, action: Action<in ProjectDependency>) =
        add(project, referenceSpec, action)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        referenceSpec: ReferenceSpec,
    ) = add(dependencyProvider, referenceSpec)

    operator fun invoke(
        dependencyProvider: ProviderConvertible<out MinimalExternalModuleDependency>,
        referenceSpec: ReferenceSpec,
        action: Action<in ExternalModuleDependency>,
    ) = add(dependencyProvider, referenceSpec, action)
}