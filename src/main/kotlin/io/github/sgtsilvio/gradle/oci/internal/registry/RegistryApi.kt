package io.github.sgtsilvio.gradle.oci.internal.registry

import io.github.sgtsilvio.gradle.oci.metadata.INDEX_MEDIA_TYPE
import io.github.sgtsilvio.gradle.oci.metadata.MANIFEST_MEDIA_TYPE
import okhttp3.*
import okio.use
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder
import org.apache.hc.core5.concurrent.FutureCallback
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Silvio Giebl
 */
class RegistryApi {

    private val httpClient = OkHttpClient.Builder().build()

    fun pullManifest(registry: String, name: String, reference: String) {
        val request = Request.Builder()
            .get()
            .url("$registry/v2/$name/manifests/$reference")
            .header(
                "Accept",
                "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
            )
            .build()
        val countDownLatch = CountDownLatch(1)
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
//                println(
//                    """
//                    |${response.isSuccessful}
//                    |${response.code}
//                    |${response.message}
//                    |${response.protocol}
//                    |---
//                    |${response.headers}
//                    |---
//                    |${response.body?.string()}
//                    |---
//                    |${response.challenges()}
//                    """.trimMargin()
//                )
                response.body?.close()
                response.close()
                countDownLatch.countDown()
            }

            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }
        })
        println("shutdown")
        httpClient.dispatcher.executorService.shutdown()
        println("shutdown")
        countDownLatch.await()
    }

    fun pullManifest2(registry: String, name: String, reference: String) {
        SimpleRequestBuilder.get()
        val request = SimpleHttpRequest.create("GET", "$registry/v2/$name/manifests/$reference")
//        val request = HttpGet("$registry/v2/$name/manifests/$reference")
        request.addHeader(
            "Accept",
            "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
        )
        val httpClient = HttpAsyncClientBuilder.create().build()
        httpClient.start()
        httpClient.use { httpClient ->
//        HttpClientBuilder.create().build().use { httpClient ->
            httpClient.execute(request, object : FutureCallback<SimpleHttpResponse> {
                override fun completed(response: SimpleHttpResponse) {
//                    println(
//                        """
//                        |${response.code}
//                        |${response.reasonPhrase}
//                        |${response.version}
//                        |---
//                        |${response.headers.contentToString()}
//                        |---
//                        |${response.bodyText}
//                        """.trimMargin()
//                    )
                }

                override fun failed(ex: Exception) {
                }

                override fun cancelled() {
                }
            }).get()
//            httpClient.execute(request) { response ->
//                println(
//                    """
//                    |${response.code}
//                    |${response.reasonPhrase}
//                    |${response.version}
//                    |---
//                    |${response.headers.contentToString()}
//                    |---
//                    """.trimMargin()
//                )
//            }
        }
    }

    fun pullManifest3(registry: String, name: String, reference: String) {
        val client = HttpClient.newBuilder().build()
        val request = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create("$registry/v2/$name/manifests/$reference"))
            .header(
                "Accept",
                "$INDEX_MEDIA_TYPE,$MANIFEST_MEDIA_TYPE,$DOCKER_MANIFEST_LIST_MEDIA_TYPE,$DOCKER_MANIFEST_MEDIA_TYPE"
            )
            .build()
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete { response, error ->
//            println(
//                """
//                |${response.statusCode()}
//                |${response.version()}
//                |---
//                |${response.headers()}
//                |---
//                |${response.body()}
//                """.trimMargin()
//            )
        }.get()
    }
}

const val DOCKER_MANIFEST_LIST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.list.v2+json"
const val DOCKER_MANIFEST_MEDIA_TYPE = "application/vnd.docker.distribution.manifest.v2+json"

fun main() {
    for (i in 0..100) {
        val t1 = System.nanoTime()
        RegistryApi().pullManifest("https://registry-1.docker.io", "registry", "2")
        val t2 = System.nanoTime()
        println(TimeUnit.NANOSECONDS.toMillis(t2 - t1))
        RegistryApi().pullManifest2("https://registry-1.docker.io", "registry", "2")
        val t3 = System.nanoTime()
        println(TimeUnit.NANOSECONDS.toMillis(t3 - t2))
        RegistryApi().pullManifest3("https://registry-1.docker.io", "registry", "2")
        val t4 = System.nanoTime()
        println(TimeUnit.NANOSECONDS.toMillis(t4 - t3))
    }
}
