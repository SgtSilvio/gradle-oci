package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.gradle.redirectOutput
import io.github.sgtsilvio.gradle.oci.metadata.OciDigest
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.oci.registry.DistributionRegistryStorage
import io.github.sgtsilvio.oci.registry.OciRegistryHandler
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import reactor.netty.http.server.HttpServer
import java.io.File
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Pulling to a Docker daemon")
abstract class PullToDockerTask @Inject constructor(private val execOperations: ExecOperations) : OciImagesTask() {

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
        val httpServer =
            HttpServer.create().handle(OciRegistryHandler(DistributionRegistryStorage(registryDataDirectory))).bindNow()
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
        registryDataDirectory.toFile().deleteRecursively()
    }

    // TODO Single task

    @get:Internal
    @get:Option(
        option = "name",
        description = "Names the image. If not specified, the imageName defined in the image definition is used.",
    )
    val imageName = project.objects.property<String>()

    @get:Internal
    @get:Option(
        option = "tag",
        description = "Tags the image. Option can be specified multiple times. The value '.' translates to the imageTag defined in the image definition. If not specified, the imageTag defined in the image definition is used.",
    )
    val imageTags = project.objects.setProperty<String>()
}
