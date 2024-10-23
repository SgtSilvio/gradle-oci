package io.github.sgtsilvio.gradle.oci.internal.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Attribute

internal fun Project.createDependency() = project.dependencies.create(project) as ProjectDependency

internal fun <T : ModuleDependency, A : Any> T.attribute(key: Attribute<A>, value: A): T {
    attributes {
        attribute(key, value)
    }
    return this
}
