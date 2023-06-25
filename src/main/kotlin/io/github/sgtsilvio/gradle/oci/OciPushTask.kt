package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.Coordinates
import io.github.sgtsilvio.gradle.oci.component.OciComponent
import io.github.sgtsilvio.gradle.oci.component.OciComponentResolver
import io.github.sgtsilvio.gradle.oci.component.decodeAsJsonToOciComponent
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRegistryApi
import io.github.sgtsilvio.gradle.oci.metadata.*
import io.github.sgtsilvio.gradle.oci.platform.Platform
import io.netty.buffer.ByteBuf
import io.netty.channel.*
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
import org.gradle.kotlin.dsl.registerIfAbsent
import org.gradle.kotlin.dsl.setProperty
import org.gradle.kotlin.dsl.submit
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.netty.NettyOutbound
import java.io.File
import java.net.URI
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

    private val pushService =
        project.gradle.sharedServices.registerIfAbsent("ociPushService-${project.path}", OciPushService::class) {}

    init {
        this.usesService(pushService)
    }

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
            val imageReference = resolvedComponent.component.imageReference
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
                                val sourceBlob = blobs[digest]
                                blobFutures += if (sourceBlob == null) {
                                    val layerFile = allLayers[digest]!!.toPath()
                                    val contentLength = Files.size(layerFile)
                                    val bodyPublisher: NettyOutbound.() -> Publisher<Void> = { sendFileChunked(layerFile, 0, contentLength) }
                                    val sourceImageName = resolvedBundle.component.imageReference.name
                                    val future = CompletableFuture<Unit>()
                                    blobs[digest] = Blob(digest, contentLength, bodyPublisher, imageName, sourceImageName, future)
                                    future
                                } else if (sourceBlob.imageName == imageName) {
                                    sourceBlob.future
                                } else {
                                    val sourceImageName = sourceBlob.imageName
                                    val contentLength = sourceBlob.contentLength
                                    val bodyPublisher = sourceBlob.bodyPublisher
                                    val future = CompletableFuture<Unit>()
                                    sourceBlob.future.thenRun {
                                        context.pushService.get().pushBlob(
                                            context,
                                            imageName,
                                            digest,
                                            sourceImageName,
                                            contentLength,
                                            bodyPublisher,
                                            future,
                                        )
                                    }
                                    future
                                }
                            }
                        }
                    }
                }
                val bundlesForPlatform = resolvedBundlesForPlatform.map { it.bundle }
                val config = createConfig(platform, bundlesForPlatform)
                val configDigest = config.digest
                val sourceBlob = blobs[configDigest]
                blobFutures += if (sourceBlob == null) {
                    val contentLength = config.data.size.toLong()
                    val bodyPublisher: NettyOutbound.() -> Publisher<Void> = { sendByteArray(Mono.just(config.data)) }
                    val future = CompletableFuture<Unit>()
                    blobs[configDigest] = Blob(configDigest, contentLength, bodyPublisher, imageName, imageName, future)
                    future
                } else if (sourceBlob.imageName == imageName) {
                    sourceBlob.future
                } else {
                    val sourceImageName = sourceBlob.imageName
                    val contentLength = sourceBlob.contentLength
                    val bodyPublisher = sourceBlob.bodyPublisher
                    val future = CompletableFuture<Unit>()
                    sourceBlob.future.thenRun {
                        context.pushService.get().pushBlob(
                            context,
                            imageName,
                            configDigest,
                            sourceImageName,
                            contentLength,
                            bodyPublisher,
                            future,
                        )
                    }
                    future
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
        for (blob in blobs.values.sortedByDescending { it.contentLength }) {
            context.pushService.get().pushBlob(
                context,
                blob.imageName,
                blob.digest,
                blob.sourceImageName,
                blob.contentLength,
                blob.bodyPublisher,
                blob.future,
            )
        }
    }

    class Blob(
        val digest: OciDigest,
        val contentLength: Long, // TODO rename to size
        val bodyPublisher: NettyOutbound.() -> Publisher<Void>,
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
        contentLength: Long,
        bodyPublisher: NettyOutbound.() -> Publisher<Void>,
        future: CompletableFuture<Unit>?,
    ) = context.workQueue.submit(context.pushService) {
        val progressLogger = context.progressLoggerFactory.newOperation(OciPushService::class.java)
        val progressPrefix = "Pushing $imageName > blob $digest"
        progressLogger.start("pushing blob", progressPrefix)
        registryApi.pushBlobIfNotPresent(
            context.registryUrl.toString(),
            imageName,
            digest,
            sourceImageName,
//            null,
            context.credentials,
            contentLength,
        ) {
            withConnection { connection ->
                connection.addHandlerFirst("progress", object : ChannelOutboundHandlerAdapter() {
                    var current = 0L
                    var lastFormatted: String? = null

                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        val newPromise = if (msg is ByteBuf) {
                            val bytes = msg.readableBytes()
                            promise.unvoid().addListener {
                                current += bytes
                                val formatted = formatBytesString(current)
                                if (formatted != lastFormatted) {
                                    lastFormatted = formatted
                                    progressLogger.progress("$progressPrefix > " + formatted + "/" + formatBytesString(contentLength))
                                }
                            }
                        } else promise
                        ctx.write(msg, newPromise)
                    }
                })
            }
            bodyPublisher()
        }.block()
        progressLogger.completed()
        future?.complete(Unit)
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
        registryApi.pushManifest(
            context.registryUrl.toString(),
            imageName,
            reference,
            context.credentials,
            OciRegistryApi.Manifest(mediaType, data),
        ).block()
        progressLogger.completed()
        future?.complete(Unit)
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
