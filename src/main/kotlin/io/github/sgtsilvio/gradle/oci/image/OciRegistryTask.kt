package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciLoopResources
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.oci.registry.DistributionRegistryStorage
import io.github.sgtsilvio.oci.registry.OciRegistryHandler
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelOption
import reactor.netty.http.server.HttpServer
import java.io.File
import java.nio.file.Path

/**
 * @author Silvio Giebl
 */
abstract class OciRegistryTask : OciImagesTask() {

    final override fun run(
        digestToLayerFile: Map<OciDigest, File>,
        images: List<OciImage>,
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
    ) {
        val registryDataDirectory = temporaryDir.toPath()
        createRegistryDataDirectory(
            digestToLayerFile,
            images,
            multiPlatformImageAndReferencesPairs,
            registryDataDirectory,
        )
        useRegistry(registryDataDirectory) { registryPort ->
            run(multiPlatformImageAndReferencesPairs, registryPort)
        }
        registryDataDirectory.toFile().deleteRecursively()
    }

    abstract fun run(
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
        registryPort: Int,
    )
}

internal fun useRegistry(registryDataDirectory: Path, block: (registryPort: Int) -> Unit) {
    val loopResources = OciLoopResources.acquire()
    try {
        val httpServer = HttpServer.create()
            .runOn(loopResources)
            .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
            .handle(OciRegistryHandler(DistributionRegistryStorage(registryDataDirectory)))
            .bindNow()
        try {
            block(httpServer.port())
        } finally {
            httpServer.disposeNow()
        }
    } finally {
        OciLoopResources.release()
    }
}
