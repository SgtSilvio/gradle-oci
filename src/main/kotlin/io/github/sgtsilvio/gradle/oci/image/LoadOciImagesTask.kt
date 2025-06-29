package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.findExecutablePath
import io.github.sgtsilvio.gradle.oci.internal.gradle.redirectOutput
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.gradle.oci.platform.toPlatformArgument
import org.apache.commons.lang3.SystemUtils
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Loading to an external Docker daemon")
abstract class LoadOciImagesTask @Inject constructor(private val execOperations: ExecOperations) : OciRegistryTask() {

    final override fun run(
        multiPlatformImageAndReferencesPairs: List<Pair<OciMultiPlatformImage, List<OciImageReference>>>,
        registryPort: Int,
    ) {
        val dockerExecutablePath = findExecutablePath("docker")
        val singlePlatform = platformSelector.orNull?.singlePlatformOrNull()
        val host = if (SystemUtils.IS_OS_WINDOWS || SystemUtils.IS_OS_MAC) "host.docker.internal" else "localhost"
        for ((_, imageReferences) in multiPlatformImageAndReferencesPairs) {
            for (imageReference in imageReferences) {
                val registryImageReference = "$host:$registryPort/$imageReference"
                execOperations.exec {
                    val arguments = mutableListOf(dockerExecutablePath, "pull", registryImageReference)
                    if (singlePlatform != null) {
                        arguments += listOf("--platform", singlePlatform.toPlatformArgument())
                    }
                    commandLine(arguments)
                    redirectOutput(logger)
                }
                execOperations.exec {
                    commandLine(dockerExecutablePath, "tag", registryImageReference, imageReference)
                    redirectOutput(logger)
                }
                execOperations.exec {
                    commandLine(dockerExecutablePath, "rmi", registryImageReference)
                    redirectOutput(logger)
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Loading to an external Docker daemon")
abstract class LoadOciImageTask @Inject constructor(
    execOperations: ExecOperations,
) : LoadOciImagesTask(execOperations), OciImageTask
