package io.github.sgtsilvio.gradle.oci.internal.gradle

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.artifacts.dependencies.ProjectDependencyInternal
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver
import org.gradle.util.GradleVersion
import org.gradle.util.Path

internal fun ModuleDependency.getDefaultCapability(
    projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
): Coordinates = when (this) {
    is ProjectDependency -> getDefaultCapability(projectDependencyPublicationResolver)
    else -> Coordinates(group ?: "", name)
}

private fun ProjectDependency.getDefaultCapability(
    projectDependencyPublicationResolver: ProjectDependencyPublicationResolver,
): Coordinates {
    val id = when {
        GradleVersion.current() >= GradleVersion.version("8.5") -> projectDependencyPublicationResolver.resolveComponent(
            ModuleVersionIdentifier::class.java,
            (this as ProjectDependencyInternal).identityPath,
        )

        GradleVersion.current() >= GradleVersion.version("8.4") -> projectDependencyPublicationResolver.resolve(
            ModuleVersionIdentifier::class.java,
            (this as ProjectDependencyInternal).identityPath,
        )

        else -> projectDependencyPublicationResolver.resolve(ModuleVersionIdentifier::class.java, this)
    }
    return Coordinates(id.group, id.name)
}

private fun <T> ProjectDependencyPublicationResolver.resolve(coordsType: Class<T>, identityPath: Path): T {
    val method =
        ProjectDependencyPublicationResolver::class.java.getMethod("resolve", Class::class.java, Path::class.java)
    return coordsType.cast(method.invoke(this, coordsType, identityPath))
}

private fun <T> ProjectDependencyPublicationResolver.resolve(coordsType: Class<T>, dependency: ProjectDependency): T {
    val method = ProjectDependencyPublicationResolver::class.java.getMethod(
        "resolve", Class::class.java, ProjectDependency::class.java
    )
    return coordsType.cast(method.invoke(this, coordsType, dependency))
}
