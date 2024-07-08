package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.dsl.OciRegistry
import io.github.sgtsilvio.gradle.oci.internal.gradle.passwordCredentials
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciRegistryHttpClient
import io.github.sgtsilvio.gradle.oci.internal.registry.Credentials
import io.github.sgtsilvio.gradle.oci.internal.registry.OciRegistryApi
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.gradle.api.Action
import org.gradle.api.credentials.PasswordCredentials
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.options.Option
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.reactivestreams.Publisher
import reactor.kotlin.core.publisher.toMono
import reactor.netty.NettyOutbound
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.io.path.fileSize

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Pushing to an external registry")
abstract class OciPushTask @Inject constructor(
    private val workerExecutor: WorkerExecutor,
) : OciImagesInputTask() {

    @get:Nested
    val registry = project.objects.newInstance<Registry>()

    interface Registry {
        @get:Input
        val url: Property<URI>

        @get:Internal
        val credentials: Property<PasswordCredentials>

        fun from(registry: OciRegistry) {
            url.set(registry.url)
            credentials.set(registry.credentials)
        }
    }

    private val pushService =
        project.gradle.sharedServices.registerIfAbsent("ociPushService-${project.path}", OciPushService::class) {}

    init {
        this.usesService(pushService)
    }

    fun registry(action: Action<in Registry>) = action.execute(registry)

    @Option(
        option = "registry",
        description = "Pushes to the registry defined with the specified name in oci.registries.",
    )
    protected fun setRegistryName(registryName: String) =
        registry.from(project.extensions.getByType(OciExtension::class).registries.list[registryName])

    @Option(option = "url", description = "Pushes to the specified registry URL.")
    protected fun setRegistryUrl(registryUrl: String) = registry.url.set(project.uri(registryUrl))

    @Option(
        option = "credentials",
        description = "Authenticates to the registry using the credentials with the specified id.",
    )
    protected fun setRegistryCredentialsId(credentialsId: String) =
        registry.credentials.set(project.providers.passwordCredentials(credentialsId))

    override fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiArchImageAndReferencesPairs: List<Pair<OciMultiArchImage, List<OciImageReference>>>,
    ) {
        val context = Context(
            pushService,
            workerExecutor.noIsolation(),
            services.get(ProgressLoggerFactory::class.java),
            registry.url.get(),
            registry.credentials.orNull?.let { Credentials(it.username!!, it.password!!) },
        )

        val blobs = HashMap<OciDigest, Blob>()
        for ((multiArchImage, imageReferences) in multiArchImageAndReferencesPairs) {
            for ((imageName, tags) in imageReferences.groupByTo(HashMap(), { it.name }, { it.tag })) {
                val manifestFutures = mutableListOf<CompletableFuture<Unit>>()
                for (image in multiArchImage.platformToImage.values) {
                    val blobFutures = mutableListOf<CompletableFuture<Unit>>()
                    for (variant in image.variants) {
                        for (layer in variant.layers) {
                            val digest = layer.descriptor.digest
                            val sourceBlob = blobs[digest]
                            blobFutures += if (sourceBlob == null) {
                                val file = layer.file.toPath()
                                val size = file.fileSize()
                                val sender: NettyOutbound.() -> Publisher<Void> = { sendFileChunked(file, 0, size) }
                                val sourceImageName = variant.metadata.imageReference.name
                                val future = CompletableFuture<Unit>()
                                blobs[digest] = Blob(digest, size, sender, imageName, sourceImageName, future)
                                future
                            } else if (sourceBlob.imageName == imageName) {
                                sourceBlob.future
                            } else {
                                val size = sourceBlob.size
                                val sourceImageName = sourceBlob.imageName
                                val sender = sourceBlob.sender
                                val future = CompletableFuture<Unit>()
                                sourceBlob.future.thenRun {
                                    context.pushService.get()
                                        .pushBlob(context, imageName, digest, size, sourceImageName, sender, future)
                                }
                                future
                            }
                        }
                    }
                    val config = image.config
                    val configDigest = config.digest
                    val sourceBlob = blobs[configDigest]
                    blobFutures += if (sourceBlob == null) {
                        val size = config.data.size.toLong()
                        val sender: NettyOutbound.() -> Publisher<Void> = { sendByteArray(config.data.toMono()) }
                        val future = CompletableFuture<Unit>()
                        blobs[configDigest] = Blob(configDigest, size, sender, imageName, imageName, future)
                        future
                    } else if (sourceBlob.imageName == imageName) {
                        sourceBlob.future
                    } else {
                        val size = sourceBlob.size
                        val sourceImageName = sourceBlob.imageName
                        val sender = sourceBlob.sender
                        val future = CompletableFuture<Unit>()
                        sourceBlob.future.thenRun {
                            context.pushService.get()
                                .pushBlob(context, imageName, configDigest, size, sourceImageName, sender, future)
                        }
                        future
                    }
                    val manifest = image.manifest
                    val manifestDigest = manifest.digest
                    val manifestMediaType = manifest.mediaType
                    val manifestData = manifest.data
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
                val index = multiArchImage.index
                for (tag in tags) {
                    val indexMediaType = index.mediaType
                    val indexData = index.data
                    CompletableFuture.allOf(*manifestFutures.toTypedArray()).thenRun {
                        context.pushService.get().pushManifest(context, imageName, tag, indexMediaType, indexData, null)
                    }
                }
            }
        }
        for (blob in blobs.values.sortedByDescending { it.size }) {
            context.pushService.get().pushBlob(
                context,
                blob.imageName,
                blob.digest,
                blob.size,
                blob.sourceImageName,
                blob.sender,
                blob.future,
            )
        }
    }

    private class Blob(
        val digest: OciDigest,
        val size: Long,
        val sender: NettyOutbound.() -> Publisher<Void>,
        val imageName: String,
        val sourceImageName: String,
        val future: CompletableFuture<Unit>,
    )

    internal class Context(
        val pushService: Provider<OciPushService>,
        val workQueue: WorkQueue,
        val progressLoggerFactory: ProgressLoggerFactory,
        val registryUrl: URI,
        val credentials: Credentials?,
    )
}

internal abstract class OciPushService : BuildService<BuildServiceParameters.None>, AutoCloseable {

    private val registryApi = OciRegistryApi(OciRegistryHttpClient.acquire())
    private val actionIdCounter = AtomicInteger()
    private val actions = ConcurrentHashMap<Int, () -> Unit>()

    fun pushBlob(
        context: OciPushTask.Context,
        imageName: String,
        digest: OciDigest,
        size: Long,
        sourceImageName: String,
        sender: NettyOutbound.() -> Publisher<Void>,
        future: CompletableFuture<Unit>?,
    ) = context.workQueue.submit(context.pushService) {
        val progressLogger = context.progressLoggerFactory.newOperation(OciPushService::class.java)
        val progressPrefix = "Pushing $imageName > blob $digest"
        progressLogger.start("pushing blob", progressPrefix)
        registryApi.pushBlobIfNotPresent(
            context.registryUrl.toString(),
            imageName,
            digest,
            size,
            sourceImageName,
            context.credentials,
        ) {
            withConnection { connection ->
                connection.addHandlerFirst("progress", object : ChannelOutboundHandlerAdapter() {
                    private var progress = 0L
                    private var lastFormattedProgress: String? = null
                    private val formattedTotal = formatBytesString(size)

                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        val newPromise = if (msg is ByteBuf) {
                            val bytes = msg.readableBytes()
                            promise.unvoid().addListener {
                                progress += bytes
                                val formattedProgress = formatBytesString(progress)
                                if (formattedProgress != lastFormattedProgress) {
                                    lastFormattedProgress = formattedProgress
                                    progressLogger.progress("$progressPrefix > $formattedProgress/$formattedTotal")
                                }
                            }
                        } else promise
                        ctx.write(msg, newPromise)
                    }
                })
            }
            sender()
        }.block()
        progressLogger.completed()
        future?.complete(Unit)
    }

    fun pushManifest(
        context: OciPushTask.Context,
        imageName: String,
        reference: String,
        mediaType: String,
        data: ByteArray,
        future: CompletableFuture<Unit>?,
    ) = context.workQueue.submit(context.pushService) {
        val progressLogger = context.progressLoggerFactory.newOperation(OciPushService::class.java)
        progressLogger.start("pushing manifest", "Pushing $imageName > manifest $reference ($mediaType)")
        registryApi.pushManifest(
            context.registryUrl.toString(),
            imageName,
            reference,
            mediaType,
            data,
            context.credentials,
        ).block()
        progressLogger.completed()
        future?.complete(Unit)
    }

    final override fun close() = OciRegistryHttpClient.release()

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

        final override fun execute() = parameters.pushService.get().actions.remove(parameters.actionId.get())!!()
    }
}

private fun formatBytesString(bytes: Long): String = when {
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
