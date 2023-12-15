package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistry
import io.github.sgtsilvio.gradle.oci.internal.json.addObject
import io.github.sgtsilvio.gradle.oci.internal.json.jsonObject
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciLoopResources
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciRegistryHttpClient
import io.github.sgtsilvio.gradle.oci.internal.registry.OciComponentRegistry
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRegistryApi
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRepositoryHandler
import io.github.sgtsilvio.gradle.oci.mapping.OciImageMappingImpl
import io.github.sgtsilvio.gradle.oci.mapping.encodeOciImageMappingData
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelOption
import io.netty.util.concurrent.FastThreadLocal
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.gradle.kotlin.dsl.*
import reactor.netty.ChannelBindException
import reactor.netty.http.server.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.*
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciRegistriesImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    project: Project,
    configurationContainer: ConfigurationContainer,
    imageMapping: OciImageMappingImpl,
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
    final override val repositoryPort: Property<Int> = objectFactory.property<Int>().convention(5123)

    private var beforeResolveInitialized = false

    init {
        project.afterEvaluate {
            afterEvaluate()
        }
        configurationContainer.configureEach {
            incoming.beforeResolve {
                if (!beforeResolveInitialized && resolvesOciImages()) {
                    beforeResolveInitialized = true
                    beforeResolve(imageMapping)
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

    private inline fun getOrCreateRegistry(name: String, init: OciRegistry.() -> Unit = {}): OciRegistry {
        var registry = list.findByName(name)
        if (registry == null) {
            registry = objectFactory.newInstance<OciRegistryImpl>(name, this)
            registry.init()
            list += registry
        }
        return registry
    }

    private fun ResolvableDependencies.resolvesOciImages() =
        attributes.getAttribute(DISTRIBUTION_TYPE_ATTRIBUTE)?.name == OCI_IMAGE_DISTRIBUTION_TYPE

    private fun afterEvaluate() {
        for (registry in list) {
            (registry as OciRegistryImpl).afterEvaluate()
        }
    }

    private fun beforeResolve(imageMapping: OciImageMappingImpl) {
        startRepository()
        for (registry in list) {
            (registry as OciRegistryImpl).beforeResolve(imageMapping)
        }
    }

    private fun startRepository() {
        try {
            val port = repositoryPort.get()
            HttpServer.create()
                .bindAddress { InetSocketAddress("localhost", port) }
                .httpRequestDecoder { it.maxHeaderSize(1_048_576) }
                .runOn(OciLoopResources.acquire())
                .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handle(OciRepositoryHandler(OciComponentRegistry(OciRegistryApi(OciRegistryHttpClient.acquire()))))
                .bindNow()
        } catch (_: ChannelBindException) {
            OciRegistryHttpClient.release() // TODO maybe do only acquire the http client if actually needed
            OciLoopResources.release()
        }
        // Netty adds a thread local to the current thread that then retains a reference to the current classloader.
        // The current classloader can then not be collected, although it has a narrower scope then the current thread.
        FastThreadLocal.destroy()
    }
}

abstract class OciRegistryImpl @Inject constructor(
    private val name: String,
    registries: OciRegistriesImpl,
    objectFactory: ObjectFactory,
    repositoryHandler: RepositoryHandler,
) : OciRegistry {

    final override val url = objectFactory.property<URI>()
    final override val credentials = objectFactory.property<PasswordCredentials>()
    final override val repository = repositoryHandler.maven {
        name = this@OciRegistryImpl.name + "OciRegistry"
        isAllowInsecureProtocol = true
        metadataSources {
            gradleMetadata()
            artifact()
        }
        content {
            onlyForAttribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
        }
    }

    private val repositoryUrl: Provider<URI> = url.zip(registries.repositoryPort) { url, repositoryPort ->
        URI(
            "http://localhost:$repositoryPort/v1/repository/" + Base64.getUrlEncoder()
                .encodeToString(url.toString().toByteArray())
        )
    }

    final override fun getName() = name

    fun afterEvaluate() {
        repository.url = repositoryUrl.get()
    }

    fun beforeResolve(imageMapping: OciImageMappingImpl) {
        repository.credentials(HttpHeaderCredentials::class) {
            name = "context"
            val credentials = credentials.orNull
            value = jsonObject {
                if (credentials != null) {
                    addObject("credentials") {
                        addString("username", credentials.username!!)
                        addString("password", credentials.password!!)
                    }
                }
                addObject("imageMapping") {
                    encodeOciImageMappingData(imageMapping.getData())
                }
            }
        }
        repository.authentication {
            create<HttpHeaderAuthentication>("header")
        }
    }
}
