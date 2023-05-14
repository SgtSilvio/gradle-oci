package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistry
import io.github.sgtsilvio.gradle.oci.internal.registry.OciComponentRegistry
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRegistryApi
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRepository
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvableDependencies
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.namedDomainObjectList
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
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
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
    final override val repositoryPort = objectFactory.property<Int>().convention(5123)

    init {
        project.afterEvaluate {
            afterEvaluate()
        }
        configurationContainer.configureEach {
            incoming.beforeResolve {
                if (resolvesOciImages()) {
                    startRepository()
                }
            }
            incoming.afterResolve {
                if (resolvesOciImages()) {
                    stopRepository()
                }
            }
        }
    }

    final override fun registry(name: String, configuration: Action<in OciRegistry>) =
        configuration.execute(getOrCreateRegistry(name))

    final override fun dockerHub(configuration: Action<in OciRegistry>) {
        val registry = getOrCreateRegistry("dockerHub")
        registry.url.convention(URI("https://registry-1.docker.io"))
        configuration.execute(registry)
    }

    private fun getOrCreateRegistry(name: String): OciRegistry =
        list.findByName(name) ?: objectFactory.newInstance<OciRegistryImpl>(name, this).also { list += it }

    private fun ResolvableDependencies.resolvesOciImages() =
        attributes.getAttribute(DISTRIBUTION_TYPE_ATTRIBUTE)?.name == OCI_IMAGE_DISTRIBUTION_TYPE

    private fun afterEvaluate() {
        for (registry in list) {
            (registry as OciRegistryImpl).afterEvaluate()
        }
    }

//    private var server: DisposableServer? = null
    private val repository = OciRepository(OciComponentRegistry(OciRegistryApi()))

    private fun startRepository() {
        // TODO start server on repositoryPort.get() if not yet started
//        server = HttpServer.create()
//            .bindAddress { InetSocketAddress("localhost", repositoryPort.get()) }
//            .route { routes ->
//                routes.get("/v1/{registryUri}/{group}/{name}/{version}/{artifact}") { request, response ->
//                    println(request)
//                    val registryUri = String(Base64.getUrlDecoder().decode(request.param("registryUri")))
//                    val group = request.param("group")!!
//                    val name = request.param("name")!!
//                    val version = request.param("version")!!
//                    val artifact = request.param("artifact")!!
//                    val registryApi = RegistryApi()
//                    val manifestFuture = registryApi.pullManifest(registryUri, "$group/$name", version, null)
//                    Mono.fromFuture(manifestFuture).doOnNext { println(it) }.then(response.sendNotFound())
//                }
//            }
//            .bindNow()
        repository.stop()
        repository.start(repositoryPort.get())
    }

    private fun stopRepository() {
        // TODO stop server if started, count beforeResolve calls via atomic integer
//        server?.disposeNow()
//        repository.stop()
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
        this.name = "${name}OciRegistry"
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
}
