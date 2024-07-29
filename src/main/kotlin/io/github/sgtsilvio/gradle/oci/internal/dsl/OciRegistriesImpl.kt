package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
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

/**
 * @author Silvio Giebl
 */
internal abstract class OciRegistriesImpl @Inject constructor(
    private val imageMapping: OciImageMappingImpl,
    private val objectFactory: ObjectFactory,
    private val repositoryHandler: RepositoryHandler,
    configurationContainer: ConfigurationContainer,
    buildServiceRegistry: BuildServiceRegistry,
    project: Project,
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
    final override val repositoryPort: Property<Int> = objectFactory.property<Int>().convention(5123)
    private val registriesService =
        buildServiceRegistry.registerIfAbsent("ociRegistriesService-${project.path}", OciRegistriesService::class) {}

    private var beforeResolveInitialized = false

    init {
        configurationContainer.configureEach {
            incoming.beforeResolve {
                if (!beforeResolveInitialized && resolvesOciImages()) {
                    beforeResolveInitialized = true
                    beforeResolve()
                }
            }
        }
    }

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
            registry = objectFactory.newInstance<OciRegistryImpl>(name, this)
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

    private fun ResolvableDependencies.resolvesOciImages() =
        attributes.getAttribute(DISTRIBUTION_TYPE_ATTRIBUTE) == OCI_IMAGE_DISTRIBUTION_TYPE

    private fun beforeResolve() {
        if (list.isNotEmpty()) {
            val registriesService = registriesService.get()
            registriesService.init(repositoryPort.get())
            val imageMappingData = imageMapping.getData()
            for (registry in list) {
                registriesService.register(registry, imageMappingData)
            }
        }
    }
}

internal abstract class OciRegistryImpl @Inject constructor(
    private val name: String,
    registries: OciRegistriesImpl,
    objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory,
    repositoryHandler: RepositoryHandler,
) : OciRegistry {

    final override val url = objectFactory.property<URI>()
    final override val finalUrl: Provider<URI> =
        providerFactory.gradleProperty(url.map(URI::toString)).map(::URI).orElse(url)
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
            artifact()
        }
        content {
            onlyForAttribute(DISTRIBUTION_TYPE_ATTRIBUTE, OCI_IMAGE_DISTRIBUTION_TYPE)
        }
    }

    final override fun getName() = name

    final override fun credentials() = credentials.set(providerFactory.passwordCredentials(name))

    final override fun optionalCredentials() = credentials.set(providerFactory.optionalPasswordCredentials(name))

    final override fun content(configuration: Action<in RepositoryContentDescriptor>) =
        repository.content(configuration)
}

private const val PORT_HTTP_HEADER_NAME = "port"

internal abstract class OciRegistriesService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val httpServers = mutableListOf<DisposableServer>()
    private val loopResources = OciLoopResources.acquire()
    private val imageMetadataRegistry = OciImageMetadataRegistry(OciRegistryApi(OciRegistryHttpClient.acquire()))

    fun init(port: Int) {
        try {
            addHttpServer(port) { request, response ->
                val redirectPort = request.requestHeaders()[PORT_HTTP_HEADER_NAME]
                response.sendRedirect("http://localhost:" + redirectPort + request.uri())
            }
        } catch (_: ChannelBindException) {
        }
    }

    fun register(registry: OciRegistry, imageMappingData: OciImageMappingData) {
        val credentials = registry.credentials.orNull?.let { Credentials(it.username!!, it.password!!) }
        val port = addHttpServer(0, OciRepositoryHandler(imageMetadataRegistry, imageMappingData, credentials)).port()
        registry.repository.credentials(HttpHeaderCredentials::class) {
            name = PORT_HTTP_HEADER_NAME
            value = port.toString()
        }
        registry.repository.authentication {
            create<HttpHeaderAuthentication>("header")
        }
    }

    private fun addHttpServer(
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
