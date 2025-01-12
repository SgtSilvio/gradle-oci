package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.gradle.redirectOutput
import io.github.sgtsilvio.gradle.oci.internal.reactor.netty.OciLoopResources
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.oci.registry.DistributionRegistryStorage
import io.github.sgtsilvio.oci.registry.OciRegistryHandler
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelOption
import org.apache.commons.lang3.SystemUtils
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import reactor.netty.http.server.HttpServer
import java.io.File
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Loading to an external Docker daemon")
abstract class LoadOciImagesTask @Inject constructor(private val execOperations: ExecOperations) : OciImagesTask() {

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
        val host = if (SystemUtils.IS_OS_WINDOWS || SystemUtils.IS_OS_MAC) "host.docker.internal" else "localhost"
        val loopResources = OciLoopResources.acquire()
        try {
            val httpServer = HttpServer.create()
                .runOn(loopResources)
                .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                .handle(OciRegistryHandler(DistributionRegistryStorage(registryDataDirectory)))
                .bindNow()
            try {
                for ((_, imageReferences) in multiPlatformImageAndReferencesPairs) {
                    for (imageReference in imageReferences) {
                        val registryImageReference = "$host:${httpServer.port()}/$imageReference"
                        execOperations.exec {
                            commandLine("docker", "pull", registryImageReference)
                            redirectOutput(logger)
                        }
                        execOperations.exec {
                            commandLine("docker", "tag", registryImageReference, imageReference)
                            redirectOutput(logger)
                        }
                        execOperations.exec {
                            commandLine("docker", "rmi", registryImageReference)
                            redirectOutput(logger)
                        }
                    }
                }
            } finally {
                httpServer.disposeNow()
            }
        } finally {
            OciLoopResources.release()
        }
        registryDataDirectory.toFile().deleteRecursively()
    }
}

@DisableCachingByDefault(because = "Loading to an external Docker daemon")
abstract class LoadOciImageTask @Inject constructor(
    execOperations: ExecOperations
) : LoadOciImagesTask(execOperations), OciImageTask
