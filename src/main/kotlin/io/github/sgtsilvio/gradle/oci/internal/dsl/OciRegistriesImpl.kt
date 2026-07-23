package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_INDEX_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistry
import io.github.sgtsilvio.gradle.oci.internal.gradle.optionalPasswordCredentials
import io.github.sgtsilvio.gradle.oci.internal.gradle.passwordCredentials
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciLoopResources
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciRegistryHttpClient
import io.github.sgtsilvio.gradle.oci.internal.registry.*
import io.github.sgtsilvio.gradle.oci.internal.registry.OCI_QUALIFIED_REGISTRY_SEGMENT
import io.github.sgtsilvio.gradle.oci.internal.registry.REGISTRY_SEPARATOR
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingData
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.util.concurrent.FastThreadLocal
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.InclusiveRepositoryContentDescriptor
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.RepositoryContentDescriptor
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.gradle.kotlin.dsl.*
import org.reactivestreams.Publisher
import reactor.netty.ChannelBindException
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerRequest
import reactor.netty.http.server.HttpServerResponse
import reactor.netty.resources.LoopResources
import java.net.InetSocketAddress
import java.net.URI
import java.util.function.BiFunction
import javax.inject.Inject

internal val OCI_IMAGE_DISTRIBUTION_TYPES = arrayOf(OCI_IMAGE_DISTRIBUTION_TYPE, OCI_IMAGE_INDEX_DISTRIBUTION_TYPE)

private const val QUALIFIED_REPOSITORY_NAME = "qualifiedOciRegistry"

/**
 * @author Silvio Giebl
 */
internal abstract class OciRegistriesImpl @Inject constructor(
    private val repositoryHandler: RepositoryHandler,
    private val providerFactory: ProviderFactory,
    private val objectFactory: ObjectFactory,
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
    private var qualifiedRepositoryOrNull: IvyArtifactRepository? = null
    internal val qualifiedRepository: IvyArtifactRepository? get() = qualifiedRepositoryOrNull
    final override val repositoryPort: Property<Int> = objectFactory.property<Int>().convention(5123)

    final override fun registry(name: String, configuration: Action<in OciRegistry>): OciRegistry {
        val registry = getOrCreateRegistry(name)
        configuration.execute(registry)
        return registry
    }

    final override fun dockerHub(configuration: Action<in OciRegistry>): OciRegistry {
        val registry = getOrCreateRegistry("dockerHub") {
            url.convention(URI("https://registry-1.docker.io"))
        }
        configuration.execute(registry)
        return registry
    }

    final override fun gitHubContainerRegistry(configuration: Action<in OciRegistry>): OciRegistry {
        val registry = getOrCreateRegistry("ghcr") {
            url.convention(URI("https://ghcr.io"))
        }
        configuration.execute(registry)
        return registry
    }

    private inline fun getOrCreateRegistry(name: String, init: OciRegistry.() -> Unit = {}): OciRegistry {
        var registry = list.findByName(name)
        if (registry == null) {
            registry =
                objectFactory.newInstance<OciRegistryImpl>(name, repositoryPort, repositoryHandler, providerFactory)
            registry.init()
            list += registry
        }
        registerQualifiedRepository()
        return registry
    }

    /**
     * Registers the repository that serves registry qualified coordinates, a coordinate whose group contains the
     * registry host, for example `ghcr.io!shopify:toxiproxy:2.12.0`. Such a coordinate does not need a declared
     * registry, which is what allows an image to be consumed without knowing where its parent images are hosted.
     *
     * The repository is registered together with the first declared registry instead of unconditionally, so that a
     * build that declares no repositories at all keeps declaring none.
     */
    internal fun registerQualifiedRepository(): IvyArtifactRepository {
        var repository = qualifiedRepositoryOrNull
        if (repository == null) {
            repository = repositoryHandler.ivy {
                name = QUALIFIED_REPOSITORY_NAME
                setUrl(repositoryPort.map { port ->
                    URI("http://localhost:$port/$OCI_REPOSITORY_VERSION/$OCI_QUALIFIED_REGISTRY_SEGMENT")
                })
                isAllowInsecureProtocol = true
                layout("gradle")
                metadataSources {
                    gradleMetadata()
                }
                content {
                    onlyForAttribute(DISTRIBUTION_TYPE_ATTRIBUTE, *OCI_IMAGE_DISTRIBUTION_TYPES)
                    includeGroupByRegex(".*$REGISTRY_SEPARATOR.*")
                }
            }
            qualifiedRepositoryOrNull = repository
        }
        return repository
    }

    final override fun OciRegistry.exclusiveContent(configuration: Action<in InclusiveRepositoryContentDescriptor>) {
        repositoryHandler.exclusiveContent {
            forRepository { repository }
            filter(configuration)
        }
    }
}

internal abstract class OciRegistryImpl @Inject constructor(
    private val name: String,
    repositoryPort: Provider<Int>,
    repositoryHandler: RepositoryHandler,
    private val providerFactory: ProviderFactory,
    objectFactory: ObjectFactory,
) : OciRegistry {

    final override val url = objectFactory.property<URI>()
    final override val finalUrl: Provider<URI> =
        providerFactory.gradleProperty(url.map { "oci.registry.mirror.$it" }).map(::URI).orElse(url)
    final override val credentials = objectFactory.property<PasswordCredentials>()
    final override val repository = repositoryHandler.ivy {
        name = this@OciRegistryImpl.name + "OciRegistry"
        setUrl(finalUrl.zip(repositoryPort) { url, repositoryPort ->
            val escapedUrl = url.toString().escapePathSegment()
            URI("http://localhost:$repositoryPort/$OCI_REPOSITORY_VERSION/$escapedUrl")
        })
        isAllowInsecureProtocol = true
        layout("gradle")
        metadataSources {
            gradleMetadata()
        }
        content {
            onlyForAttribute(DISTRIBUTION_TYPE_ATTRIBUTE, *OCI_IMAGE_DISTRIBUTION_TYPES)
            // a registry qualified coordinate names its registry, so it is served by the qualified repository
            excludeGroupByRegex(".*$REGISTRY_SEPARATOR.*")
        }
    }

    final override fun getName() = name

    final override fun credentials() = credentials.set(providerFactory.passwordCredentials(name))

    final override fun optionalCredentials() = credentials.set(providerFactory.optionalPasswordCredentials(name))

    final override fun content(configuration: Action<in RepositoryContentDescriptor>) =
        repository.content(configuration)
}

private const val SERVICE_BASE_NAME = "ociRegistriesService"
private const val PORT_HTTP_HEADER_NAME = "port"

internal fun OciRegistriesService(
    buildServiceRegistry: BuildServiceRegistry,
    name: String,
    registries: OciRegistriesImpl,
    imageMapping: OciImageMappingImpl,
): OciRegistriesService {
    val registriesService = buildServiceRegistry.registerIfAbsent(name, OciRegistriesService::class) {}.get()
    registriesService.init(registries, imageMapping)
    return registriesService
}

internal abstract class OciRegistriesService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private var state: State? = null

    sealed class State(val registries: OciRegistriesImpl) {

        class Initialized(
            registries: OciRegistriesImpl,
            val imageMapping: OciImageMappingImpl,
        ) : State(registries)

        class Started(
            registries: OciRegistriesImpl,
            val httpServers: List<DisposableServer>,
        ) : State(registries)

        class NoRegistries(registries: OciRegistriesImpl) : State(registries)

        class Closed(registries: OciRegistriesImpl) : State(registries)
    }

    fun init(registries: OciRegistriesImpl, imageMapping: OciImageMappingImpl) = synchronized(this) {
        if (state != null) {
            throw IllegalStateException("OciRegistriesService.init must be called exactly once immediately after creation")
        }
        state = State.Initialized(registries, imageMapping)
    }

    val registries: OciRegistriesImpl
        get() = synchronized(this) {
            state?.registries
                ?: throw IllegalStateException("OciRegistriesService.registries must be called after init")
        }

    fun start() = synchronized(this) {
        val currentState = state
            ?: throw IllegalStateException("OciRegistriesService.start must be called after init")
        if (currentState !is State.Initialized) {
            return
        }
        val registries = currentState.registries
        val qualifiedRepository = registries.qualifiedRepository
        if (registries.list.isEmpty() && (qualifiedRepository == null)) {
            state = State.NoRegistries(registries)
        } else {
            val loopResources = OciLoopResources.acquire()
            val httpClient = OciRegistryHttpClient.acquire()
            val httpServers = mutableListOf<DisposableServer>()
            state = State.Started(registries, httpServers)
            val redirectServer = startRedirectServer(registries.repositoryPort.get(), loopResources)
            if (redirectServer != null) {
                httpServers += redirectServer
            }
            val imageMetadataRegistry = OciImageMetadataRegistry(OciRegistryApi(httpClient))
            val imageMappingData = currentState.imageMapping.getData()
            for (registry in registries.list) {
                httpServers += startRegistryServer(registry, loopResources, imageMetadataRegistry, imageMappingData)
            }
            if (qualifiedRepository != null) {
                val credentialsByRegistryHost = registries.list.mapNotNull { registry ->
                    val credentials = registry.credentials.orNull ?: return@mapNotNull null
                    registry.finalUrl.get().host to Credentials(credentials.username!!, credentials.password!!)
                }.toMap()
                httpServers += startQualifiedRegistryServer(
                    qualifiedRepository,
                    loopResources,
                    imageMetadataRegistry,
                    imageMappingData,
                    credentialsByRegistryHost,
                )
            }
        }
    }

    private fun startRedirectServer(port: Int, loopResources: LoopResources): DisposableServer? {
        return try {
            startHttpServer(port, loopResources) { request, response ->
                val redirectPort = request.requestHeaders()[PORT_HTTP_HEADER_NAME]
                response.sendRedirect("http://localhost:" + redirectPort + request.uri())
            }
        } catch (_: ChannelBindException) {
            null
        }
    }

    private fun startRegistryServer(
        registry: OciRegistry,
        loopResources: LoopResources,
        imageMetadataRegistry: OciImageMetadataRegistry,
        imageMappingData: OciImageMappingData,
    ): DisposableServer {
        val credentials = registry.credentials.orNull?.let { Credentials(it.username!!, it.password!!) }
        val httpServer = startHttpServer(
            0,
            loopResources,
            OciRepositoryHandler(imageMetadataRegistry, imageMappingData, credentials),
        )
        registry.repository.credentials(HttpHeaderCredentials::class) {
            name = PORT_HTTP_HEADER_NAME
            value = httpServer.port().toString()
        }
        registry.repository.authentication {
            create<HttpHeaderAuthentication>("header")
        }
        return httpServer
    }

    /**
     * Serves the coordinates that contain the registry themselves, so one server is enough for all of their registries.
     * Such an image is pulled with the credentials of a declared registry of the same host, anonymously otherwise.
     */
    private fun startQualifiedRegistryServer(
        repository: IvyArtifactRepository,
        loopResources: LoopResources,
        imageMetadataRegistry: OciImageMetadataRegistry,
        imageMappingData: OciImageMappingData,
        credentialsByRegistryHost: Map<String, Credentials>,
    ): DisposableServer {
        val httpServer = startHttpServer(
            0,
            loopResources,
            OciRepositoryHandler(imageMetadataRegistry, imageMappingData, null, true, credentialsByRegistryHost),
        )
        repository.credentials(HttpHeaderCredentials::class) {
            name = PORT_HTTP_HEADER_NAME
            value = httpServer.port().toString()
        }
        repository.authentication {
            create<HttpHeaderAuthentication>("header")
        }
        return httpServer
    }

    private fun startHttpServer(
        port: Int,
        loopResources: LoopResources,
        handler: BiFunction<in HttpServerRequest, in HttpServerResponse, out Publisher<Void>>,
    ): DisposableServer {
        return try {
            HttpServer.create()
                .bindAddress { InetSocketAddress("localhost", port) }
                .runOn(loopResources)
                .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handle(handler)
                .bindNow()
        } finally {
            // Netty adds a thread local to the current thread that then retains a reference to the current classloader.
            // The current classloader can then not be collected, although it has a narrower scope then the current thread.
            FastThreadLocal.destroy()
        }
    }

    final override fun close() = synchronized(this) {
        val currentState = state
            ?: throw IllegalStateException("OciRegistriesService.close must be called after init")
        if (currentState is State.Started) {
            for (httpServer in currentState.httpServers) {
                httpServer.disposeNow()
            }
            OciRegistryHttpClient.release()
            OciLoopResources.release()
        }
        state = State.Closed(currentState.registries)
    }
}

internal fun setupSettingsOciRegistries(
    buildServiceRegistry: BuildServiceRegistry,
    registries: OciRegistriesImpl,
    imageMapping: OciImageMappingImpl,
) {
    // a repository of the settings is shared by all projects and never conflicts with RepositoriesMode
    registries.registerQualifiedRepository()
    OciRegistriesService(buildServiceRegistry, SERVICE_BASE_NAME, registries, imageMapping)
}

internal fun setupProjectOciRegistries(
    project: Project,
    registries: OciRegistriesImpl,
    imageMapping: OciImageMappingImpl,
) {
    val settingsRegistriesService = project.settingsRegistriesService
    val registriesService = OciRegistriesService(
        project.gradle.sharedServices,
        "$SERVICE_BASE_NAME-${project.path}",
        registries,
        imageMapping,
    )
    project.configurations.configureEach {
        incoming.beforeResolve {
            if (resolvesOciImages()) {
                settingsRegistriesService?.start()
                registriesService.start()
            }
        }
    }
}

internal val Project.settingsRegistriesService: OciRegistriesService?
    get() = gradle.sharedServices.registrations.findByName(SERVICE_BASE_NAME)?.service?.get() as? OciRegistriesService

private fun ResolvableDependencies.resolvesOciImages() =
    attributes.getAttribute(DISTRIBUTION_TYPE_ATTRIBUTE) in OCI_IMAGE_DISTRIBUTION_TYPES
