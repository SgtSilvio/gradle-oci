package io.github.sgtsilvio.gradle.oci.internal.reactor.netty

import io.github.sgtsilvio.gradle.oci.internal.Resource
import reactor.core.scheduler.Schedulers
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/**
 * @author Silvio Giebl
 */
object OciRegistryHttpClient : Resource<HttpClient>() {

    override fun create(): HttpClient {
        val connectionProvider = ConnectionProvider.builder("oci-registry")
            .maxConnections(100)
            .maxIdleTime(Duration.ofSeconds(3))
            .lifo()
            .build()
        val loopResources = OciLoopResources.acquire()
        return HttpClient.create(connectionProvider).runOn(loopResources)
    }

    override fun HttpClient.destroy() {
        val configuration = configuration()
        configuration.connectionProvider().dispose()
        configuration.resolver()?.close()
        OciLoopResources.release()
        Schedulers.shutdownNow()
    }
}
