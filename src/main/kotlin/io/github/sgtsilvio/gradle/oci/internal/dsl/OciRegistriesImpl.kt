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
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingData
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.util.concurrent.FastThreadLocal
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectList
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.InclusiveRepositoryContentDescriptor
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
import java.net.InetSocketAddress
import java.net.URI
import java.util.function.BiFunction
import javax.inject.Inject

internal val OCI_IMAGE_DISTRIBUTION_TYPES = arrayOf(OCI_IMAGE_DISTRIBUTION_TYPE, OCI_IMAGE_INDEX_DISTRIBUTION_TYPE)

/**
 * @author Silvio Giebl
 */
internal abstract class OciRegistriesImpl @Inject constructor(
    private val repositoryHandler: RepositoryHandler,
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
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
            registry = objectFactory.newInstance<OciRegistryImpl>(name, this, repositoryHandler, providerFactory)
            registry.init()
            list += registry
        }
        return registry
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
    registries: OciRegistriesImpl,
    repositoryHandler: RepositoryHandler,
    objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
) : OciRegistry {

    final override val url = objectFactory.property<URI>()
    final override val finalUrl: Provider<URI> =
        providerFactory.gradleProperty(url.map { "oci.registry.mirror.$it" }).map(::URI).orElse(url)
    final override val credentials = objectFactory.property<PasswordCredentials>()
    final override val repository = repositoryHandler.ivy {
        name = this@OciRegistryImpl.name + "OciRegistry"
        setUrl(finalUrl.zip(registries.repositoryPort) { url, repositoryPort ->
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
    registries: NamedDomainObjectList<OciRegistry>,
    repositoryPort: Property<Int>,
    imageMapping: OciImageMappingImpl,
): OciRegistriesService {
    val registriesService = buildServiceRegistry.registerIfAbsent(name, OciRegistriesService::class) {}.get()
    registriesService.init(registries, repositoryPort, imageMapping)
    return registriesService
}

internal abstract class OciRegistriesService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    lateinit var registries: NamedDomainObjectList<OciRegistry>
    lateinit var repositoryPort: Property<Int>
    lateinit var imageMapping: OciImageMappingImpl
    private var isStarted = false
    private val httpServers = mutableListOf<DisposableServer>()
    private val loopResources = OciLoopResources.acquire()
    private val imageMetadataRegistry = OciImageMetadataRegistry(OciRegistryApi(OciRegistryHttpClient.acquire()))

    fun init(
        registries: NamedDomainObjectList<OciRegistry>,
        repositoryPort: Property<Int>,
        imageMapping: OciImageMappingImpl,
    ) {
        this.registries = registries
        this.repositoryPort = repositoryPort
        this.imageMapping = imageMapping
    }

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true
        if (registries.isNotEmpty()) {
            startRedirectServer(repositoryPort.get())
            val imageMappingData = imageMapping.getData()
            for (registry in registries) {
                startRegistryServer(registry, imageMappingData)
            }
        }
    }

    private fun startRedirectServer(port: Int) {
        try {
            startHttpServer(port) { request, response ->
                val redirectPort = request.requestHeaders()[PORT_HTTP_HEADER_NAME]
                response.sendRedirect("http://localhost:" + redirectPort + request.uri())
            }
        } catch (_: ChannelBindException) {
        }
    }

    private fun startRegistryServer(registry: OciRegistry, imageMappingData: OciImageMappingData) {
        val credentials = registry.credentials.orNull?.let { Credentials(it.username!!, it.password!!) }
        val port = startHttpServer(0, OciRepositoryHandler(imageMetadataRegistry, imageMappingData, credentials)).port()
        registry.repository.credentials(HttpHeaderCredentials::class) {
            name = PORT_HTTP_HEADER_NAME
            value = port.toString()
        }
        registry.repository.authentication {
            create<HttpHeaderAuthentication>("header")
        }
    }

    private fun startHttpServer(
        port: Int,
        handler: BiFunction<in HttpServerRequest, in HttpServerResponse, out Publisher<Void>>,
    ): DisposableServer {
        return try {
            val httpServer = HttpServer.create()
                .bindAddress { InetSocketAddress("localhost", port) }
                .runOn(loopResources)
                .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handle(handler)
                .bindNow()
            httpServers += httpServer
            httpServer
        } finally {
            // Netty adds a thread local to the current thread that then retains a reference to the current classloader.
            // The current classloader can then not be collected, although it has a narrower scope then the current thread.
            FastThreadLocal.destroy()
        }
    }

    final override fun close() {
        for (httpServer in httpServers) {
            httpServer.disposeNow()
        }
        httpServers.clear()
        OciRegistryHttpClient.release()
        OciLoopResources.release()
    }
}

internal fun setupSettingsOciRegistries(
    buildServiceRegistry: BuildServiceRegistry,
    registries: OciRegistries,
    imageMapping: OciImageMappingImpl,
) {
    OciRegistriesService(
        buildServiceRegistry,
        SERVICE_BASE_NAME,
        registries.list,
        registries.repositoryPort,
        imageMapping,
    )
}

internal fun setupProjectOciRegistries(
    buildServiceRegistry: BuildServiceRegistry,
    project: Project,
    configurationContainer: ConfigurationContainer,
    registries: OciRegistries,
    imageMapping: OciImageMappingImpl,
) {
    configurationContainer.configureEach {
        incoming.beforeResolve {
            if (resolvesOciImages()) {
                val settingsRegistration = buildServiceRegistry.registrations.findByName(SERVICE_BASE_NAME)
                if (settingsRegistration != null) {
                    (settingsRegistration.service.get() as OciRegistriesService).start()
                }
                OciRegistriesService(
                    buildServiceRegistry,
                    "$SERVICE_BASE_NAME-${project.path}",
                    registries.list,
                    registries.repositoryPort,
                    imageMapping,
                ).start()
            }
        }
    }
}

private fun ResolvableDependencies.resolvesOciImages() =
    attributes.getAttribute(DISTRIBUTION_TYPE_ATTRIBUTE) in OCI_IMAGE_DISTRIBUTION_TYPES
