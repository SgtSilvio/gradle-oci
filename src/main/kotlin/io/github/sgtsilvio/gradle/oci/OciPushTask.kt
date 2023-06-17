package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.OciComponentResolver
import io.github.sgtsilvio.gradle.oci.component.decodeAsJsonToOciComponent
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRegistryApi
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.*
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import org.gradle.kotlin.dsl.submit
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.reactivestreams.FlowAdapters
import reactor.core.publisher.Flux
import reactor.netty.http.server.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Pushing to an external registry")
abstract class OciPushTask @Inject constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val imageFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:Input
    val rootCapabilities = project.objects.setProperty<Coordinates>()

    @get:Input
    val registryUrl = project.objects.property<URI>()

    @get:Internal
    val credentials = project.objects.property<PasswordCredentials>()

    @get:Internal
//    @get:ServiceReference
    val pushService = project.objects.property<OciPushService>()

    @TaskAction
    protected fun run() {
        val context = Context(
            pushService,
            workerExecutor.noIsolation(),
            services.get(ProgressLoggerFactory::class.java),
            registryUrl.get(),
            credentials.orNull?.let { OciRegistryApi.Credentials(it.username!!, it.password!!) },
        )

        val componentWithLayersList = findComponents(imageFiles)
        val allLayers = collectLayers(componentWithLayersList)
        val rootCapabilities = rootCapabilities.get()
        val componentResolver = OciComponentResolver()
        for ((component, _) in componentWithLayersList) {
            componentResolver.addComponent(component)
        }
        val blobs = hashMapOf<OciDigest, Blob>()
        for (rootCapability in rootCapabilities) {
            val resolvedComponent = componentResolver.resolve(rootCapability)
            val imageReference = resolvedComponent.component.imageReference // TODO mapping
            val imageName = imageReference.name
            val layerDigests = hashSetOf<OciDigest>()
            val manifests = mutableListOf<Pair<Platform, OciDataDescriptor>>()
            val manifestFutures = mutableListOf<CompletableFuture<Unit>>()
            for (platform in resolvedComponent.platforms) {
                val resolvedBundlesForPlatform = resolvedComponent.collectBundlesForPlatform(platform)

                val blobFutures = mutableListOf<CompletableFuture<Unit>>()
                for (resolvedBundle in resolvedBundlesForPlatform) {
                    for (layer in resolvedBundle.bundle.layers) {
                        layer.descriptor?.let { (_, digest) ->
                            if (layerDigests.add(digest)) {
                                val layerFile = allLayers[digest]!!
                                val layerPublisher = BodyPublishers.ofFile(layerFile.toPath())
                                val layerFuture = CompletableFuture<Unit>()
                                blobFutures += layerFuture
                                val sourceBlob = blobs[digest]
                                if (sourceBlob == null) {
                                    val sourceImageName = resolvedBundle.component.imageReference.name
                                    blobs[digest] = Blob(digest, layerPublisher, imageName, sourceImageName, layerFuture)
                                } else {
                                    // TODO if imageName == sourceBlob.imageName use blob.layerFuture instead of layerFuture and do not push again
                                    val sourceImageName = sourceBlob.imageName
                                    sourceBlob.future.thenRun {
                                        context.pushService.get().pushBlob(
                                            context,
                                            imageName,
                                            digest,
                                            sourceImageName,
                                            layerPublisher,
                                            layerFuture,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val bundlesForPlatform = resolvedBundlesForPlatform.map { it.bundle }
                val config = createConfig(platform, bundlesForPlatform)
                val configDigest = config.digest
                val configPublisher = BodyPublishers.ofByteArray(config.data)
                val configFuture = CompletableFuture<Unit>()
                blobFutures += configFuture
                val sourceBlob = blobs[configDigest]
                if (sourceBlob == null) {
                    blobs[configDigest] = Blob(configDigest, configPublisher, imageName, imageName, configFuture)
                } else {
                    val sourceImageName = sourceBlob.imageName
                    sourceBlob.future.thenRun {
                        context.pushService.get().pushBlob(
                            context,
                            imageName,
                            configDigest,
                            sourceImageName,
                            configPublisher,
                            configFuture,
                        )
                    }
                }

                val manifest = createManifest(config, bundlesForPlatform)
                manifests += Pair(platform, manifest)
                val manifestDigest = manifest.digest
                val manifestData = String(manifest.data)
                val manifestMediaType = manifest.mediaType
                val manifestFuture = CompletableFuture<Unit>()
                manifestFutures += manifestFuture
                CompletableFuture.allOf(*blobFutures.toTypedArray()).thenRun {
                    context.pushService.get().pushManifest(
                        context,
                        imageName,
                        manifestDigest.toString(),
                        manifestMediaType,
                        manifestData,
                        manifestFuture,
                    )
                }
            }
            val index = createIndex(manifests, resolvedComponent.component)
            val indexData = String(index.data)
            val indexMediaType = index.mediaType
            CompletableFuture.allOf(*manifestFutures.toTypedArray()).thenRun {
                context.pushService.get().pushManifest(
                    context,
                    imageName,
                    imageReference.tag,
                    indexMediaType,
                    indexData,
                    null,
                )
            }
        }
        for (blob in blobs.values.sortedByDescending { it.data.contentLength() }) {
            context.pushService.get().pushBlob(
                context,
                blob.imageName,
                blob.digest,
                blob.sourceImageName,
                blob.data,
                blob.future,
            )
        }
    }

    class Blob(
        val digest: OciDigest,
        val data: BodyPublisher,
        val imageName: String,
        val sourceImageName: String,
        val future: CompletableFuture<Unit>,
    )

    class Context(
        val pushService: Provider<OciPushService>,
        val workQueue: WorkQueue,
        val progressLoggerFactory: ProgressLoggerFactory,
        val registryUrl: URI,
        val credentials: OciRegistryApi.Credentials?,
    )
}

internal fun findComponents(ociFiles: Iterable<File>): List<OciComponentWithLayers> {
    val componentWithLayersList = mutableListOf<OciComponentWithLayers>()
    val iterator = ociFiles.iterator()
    while (iterator.hasNext()) {
        val component = iterator.next().readText().decodeAsJsonToOciComponent()
        val digestToLayer = hashMapOf<OciDigest, File>()
        for (layer in component.allLayers) {
            layer.descriptor?.let {
                val digest = it.digest
                if (digest !in digestToLayer) {
                    check(iterator.hasNext()) { "ociFiles are missing layers referenced in components" }
                    digestToLayer[digest] = iterator.next()
                }
            }
        }
        componentWithLayersList += OciComponentWithLayers(component, digestToLayer)
    }
    return componentWithLayersList
}

internal data class OciComponentWithLayers(val component: OciComponent, val digestToLayer: Map<OciDigest, File>)

internal fun collectLayers(componentWithLayersList: List<OciComponentWithLayers>): Map<OciDigest, File> {
    val allDigestToLayer = hashMapOf<OciDigest, File>()
    for ((_, digestToLayer) in componentWithLayersList) {
        for ((digest, layer) in digestToLayer) {
            val prevLayer = allDigestToLayer.putIfAbsent(digest, layer)
            if ((prevLayer != null) && (layer != prevLayer)) {
                if (FileUtils.contentEquals(prevLayer, layer)) {
//                    logger.warn("the same layer ($digest) should not be provided by multiple components") // TODO logger
                } else {
                    throw IllegalStateException("hash collision for digest $digest: expected file contents of $prevLayer and $layer to be the same")
                }
            }
        }
    }
    return allDigestToLayer
}

internal val OciComponent.allLayers
    get() = when (val bundleOrPlatformBundles = bundleOrPlatformBundles) {
        is OciComponent.Bundle -> bundleOrPlatformBundles.layers.asSequence()
        is OciComponent.PlatformBundles -> bundleOrPlatformBundles.map.values.asSequence().flatMap { it.layers }
    }

abstract class OciPushService : BuildService<BuildServiceParameters.None> {

    private val registryApi = OciRegistryApi()
    private val actionIdCounter = AtomicInteger()
    private val actions = ConcurrentHashMap<Int, () -> Unit>()

    fun pushBlob(
        context: OciPushTask.Context,
        imageName: String,
        digest: OciDigest,
        sourceImageName: String,
        bodyPublisher: BodyPublisher,
        future: CompletableFuture<Unit>?,
    ) = context.workQueue.submit(context.pushService) {
        val progressLogger = context.progressLoggerFactory.newOperation(OciPushService::class.java)
        val progressPrefix = "Pushing $imageName > blob $digest"
        progressLogger.start("pushing blob", progressPrefix)
        val pushFuture = registryApi.pushBlobIfNotPresent(
            context.registryUrl.toString(),
            imageName,
            digest,
            sourceImageName,
            context.credentials,
            ProgressBodyPublisher(bodyPublisher) { current, total ->
                progressLogger.progress("$progressPrefix > " + formatBytesString(current) + "/" + formatBytesString(total))
            },
        )
        try {
            try {
                pushFuture.get(10, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                println("cancel")
                pushFuture.cancel(true)
                Thread.sleep(100000)
            }
            pushFuture.get()
            progressLogger.completed()
            future?.complete(Unit)
        } catch (e: InterruptedException) {
            pushFuture.cancel(true)
        }
    }

    fun pushManifest(
        context: OciPushTask.Context,
        imageName: String,
        reference: String,
        mediaType: String,
        data: String,
        future: CompletableFuture<Unit>?,
    ) = context.workQueue.submit(context.pushService) {
        val progressLogger = context.progressLoggerFactory.newOperation(OciPushService::class.java)
        progressLogger.start("pushing manifest", "Pushing $imageName > manifest $reference ($mediaType)")
        val pushFuture = registryApi.pushManifest(
            context.registryUrl.toString(),
            imageName,
            reference,
            context.credentials,
            OciRegistryApi.Manifest(mediaType, data),
        )
        try {
            pushFuture.get()
            progressLogger.completed()
            future?.complete(Unit)
        } catch (e: InterruptedException) {
            pushFuture.cancel(true)
        }
    }

    private fun WorkQueue.submit(pushService: Provider<OciPushService>, action: () -> Unit) {
        val actionId = actionIdCounter.getAndIncrement()
        actions[actionId] = action
        submit(Action::class) {
            this.actionId.set(actionId)
            this.pushService.set(pushService)
        }
    }

    abstract class Action : WorkAction<Action.Parameters> {
        interface Parameters : WorkParameters {
            val actionId: Property<Int>
            val pushService: Property<OciPushService>
        }

        override fun execute() = parameters.pushService.get().actions.remove(parameters.actionId.get())!!.invoke()
    }
}

fun formatBytesString(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> {
        val hundredBytes = bytes / 100
        val kileBytes = hundredBytes / 10
        val tenthKiloBytes = hundredBytes % 10
        if (tenthKiloBytes == 0L) "$kileBytes KB" else "$kileBytes.$tenthKiloBytes KB"
    }

    else -> {
        val hundredKiloBytesBytes = bytes / 100_000
        val megaBytes = hundredKiloBytesBytes / 10
        val tenthMegaBytes = hundredKiloBytesBytes % 10
        if (tenthMegaBytes == 0L) "$megaBytes MB" else "$megaBytes.$tenthMegaBytes MB"
    }
}

class ProgressBodyPublisher(
    private val delegate: BodyPublisher,
    private val progressCallback: (current: Long, total: Long) -> Unit,
) : BodyPublisher {

    override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) =
        delegate.subscribe(Subscriber(subscriber, contentLength(), progressCallback))

    override fun contentLength() = delegate.contentLength()

    private class Subscriber(
        private val delegate: Flow.Subscriber<in ByteBuffer>,
        private val totalBytes: Long,
        private val progressCallback: (current: Long, total: Long) -> Unit,
    ) : Flow.Subscriber<ByteBuffer> {
        private var currentBytes = 0L

        override fun onSubscribe(subscription: Flow.Subscription) {
            delegate.onSubscribe(subscription)
            progressCallback.invoke(currentBytes, totalBytes)
        }

        override fun onNext(item: ByteBuffer) {
            currentBytes += item.remaining()
            delegate.onNext(item)
            progressCallback.invoke(currentBytes, totalBytes)
        }

        override fun onError(throwable: Throwable) {
            delegate.onError(throwable)
            progressCallback.invoke(-1, totalBytes)
        }

        override fun onComplete() = delegate.onComplete()
    }
}



fun main() {
    HttpServer.create()
        .bindAddress { InetSocketAddress("localhost", 12345) }
        .handle { request, response -> request.receiveContent().doOnNext { Thread.sleep(1) }.then(response.send()) }
        .bindNow()

    val tempFile = Files.createTempFile("foo", "bar")
    Files.write(tempFile, ByteArray(10_000_000))
    val size = Files.size(tempFile)
    var current = 0L

//    Thread {
//        for (i in 0..10) {
//            Thread.sleep(1000)
//            println("this is a test $i")
//        }
//    }.start()

    val timeBefore = System.nanoTime()
    HttpClient.newHttpClient().sendAsync(
        HttpRequest.newBuilder()
            .uri(URI("http://localhost:12345/test"))
            .POST(
                BodyPublishers.fromPublisher(
                    FlowAdapters.toFlowPublisher(
                        Flux.from(FlowAdapters.toPublisher(BodyPublishers.ofFile(tempFile))).doOnNext {
                            val before = current * 100 / size
                            current += it.remaining()
                            val after = current * 100 / size
                            if (after != before) {
                                val progressBar = "|" + "=".repeat(after.toInt() - 1) + ">" + " ".repeat(100 - after.toInt()) + "|"
                                print("\u001B[1A\r$progressBar $after%\n$progressBar $after%")
                            }
                        }
                    ),
                    size),
            ).build(),
        BodyHandlers.discarding(),
    ).get()
    val timeAfter = System.nanoTime()
    println()
    println((timeAfter - timeBefore) / 1_000_000_000)
    println("\u001B[1A\rtest")
    println("\u001B[1m\u001B[3m\u001B[4m\u001B[9mtest")
    println("\u001B[1;37;42mtest")
}
