package io.github.sgtsilvio.gradle.oci.internal.gradle

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver

internal fun ModuleDependency.getAnyDeclaredCapability(
    projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
): Coordinates {
    val capabilities = requestedCapabilities
    return if (capabilities.isEmpty()) {
        getDefaultCapability(projectDependencyPublicationResolver)
    } else {
        val capability = capabilities.first()
        Coordinates(capability.group, capability.name)
    }
}

internal fun ModuleDependency.getDefaultCapability(
    projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
): Coordinates {
    return if (this is ProjectDependency) {
        val id = projectDependencyPublicationResolver.resolve(ModuleVersionIdentifier::class.java, this)
        Coordinates(id.group, id.name)
    } else {
        Coordinates(group ?: "", name)
    }
}
