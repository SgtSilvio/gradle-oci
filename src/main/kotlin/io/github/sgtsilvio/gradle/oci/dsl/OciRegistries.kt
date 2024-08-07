package io.github.sgtsilvio.gradle.oci.dsl

import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.artifacts.repositories.InclusiveRepositoryContentDescriptor
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.RepositoryContentDescriptor
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.net.URI

/**
 * @author Silvio Giebl
 */
interface OciRegistries {
    val list: NamedDomainObjectList<OciRegistry>
    val repositoryPort: Property<Int>

    fun registry(name: String, configuration: Action<in OciRegistry>): OciRegistry
    fun dockerHub(configuration: Action<in OciRegistry>): OciRegistry
    fun gitHubContainerRegistry(configuration: Action<in OciRegistry>): OciRegistry

    fun OciRegistry.exclusiveContent(configuration: Action<in InclusiveRepositoryContentDescriptor>)
}

interface OciRegistry : Named {
    val url: Property<URI>
    val finalUrl: Provider<URI>
    val credentials: Property<PasswordCredentials>
    val repository: IvyArtifactRepository

    fun credentials()
    fun optionalCredentials()
    fun content(configuration: Action<in RepositoryContentDescriptor>)
}
