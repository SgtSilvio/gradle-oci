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
        val loopResources = OciLoopResources.acquire()
        try {
            val httpServer = HttpServer.create()
                .runOn(loopResources)
                .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handle(OciRegistryHandler(DistributionRegistryStorage(registryDataDirectory)))
                .bindNow()
            try {
                run(multiPlatformImageAndReferencesPairs, httpServer.port())
            } finally {
                httpServer.disposeNow()
            }
        } finally {
            OciLoopResources.release()
        }
        registryDataDirectory.toFile().deleteRecursively()
    }

    abstract fun run(
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
        registryPort: Int,
    )
}
