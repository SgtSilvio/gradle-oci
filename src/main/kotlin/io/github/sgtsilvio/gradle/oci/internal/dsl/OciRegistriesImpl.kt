package io.github.sgtsilvio.gradle.oci.internal.dsl

import com.sun.net.httpserver.HttpServer
import io.github.sgtsilvio.gradle.oci.attributes.DISTRIBUTION_TYPE_ATTRIBUTE
import io.github.sgtsilvio.gradle.oci.attributes.OCI_IMAGE_DISTRIBUTION_TYPE
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistries
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistry
import org.gradle.api.Action
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
abstract class OciRegistriesImpl @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val repositoryHandler: RepositoryHandler,
) : OciRegistries {
    final override val list = objectFactory.namedDomainObjectList(OciRegistry::class)
    final override val repositoryPort = objectFactory.property<Int>().convention(5123)

    final override fun registry(name: String, configuration: Action<in OciRegistry>) =
        configuration.execute(getOrCreateRegistry(name))

    final override fun dockerHub(configuration: Action<in OciRegistry>) {
        val registry = getOrCreateRegistry("dockerHub")
        registry.url.convention(URI("https://registry-1.docker.io"))
        configuration.execute(registry)
    }

    private fun getOrCreateRegistry(name: String): OciRegistry =
        if (name in list.names) list[name] else createRegistry(name).also { list += it }

    private fun createRegistry(name: String): OciRegistryImpl {
        val repository = repositoryHandler.maven {
            this.name = "${name}OciRegistry"
            url = URI("http://localhost:${repositoryPort.get()}/$name") // TODO not name because caching is determined based on url => url should be part of it
            isAllowInsecureProtocol = true
            metadataSources {
                gradleMetadata()
                artifact()
            }
            content {
                onlyForAttribute(DISTRIBUTION_TYPE_ATTRIBUTE, objectFactory.named(OCI_IMAGE_DISTRIBUTION_TYPE))
            }
        }
        return objectFactory.newInstance(name, repository)
    }
}

abstract class OciRegistryImpl @Inject constructor(
    private val name: String,
    final override val repository: MavenArtifactRepository,
) : OciRegistry {

    override fun getName() = name
}


fun main() {
    println(URI("http", null, "localhost", 5123, "/https://registry-1.docker.io", null, null))
    println(URLEncoder.encode("https://registry-1.docker.io", StandardCharsets.UTF_8.name()))
    val server = HttpServer.create(InetSocketAddress(5123), 0)
    server.executor = null
    server.createContext("/") {
        println(it.requestURI)
        it.sendResponseHeaders(200, 0)
    }
    server.start()
    val connection = URI("http", null, "localhost", 5123, "/https://registry-1.docker.io", null, null).toURL()
        .openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connect()
    println(connection.responseCode)
    Thread.sleep(1000)
    server.stop(0)
}