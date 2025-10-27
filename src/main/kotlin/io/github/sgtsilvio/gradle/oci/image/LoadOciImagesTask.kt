package io.github.sgtsilvio.gradle.oci.image

import io.github.sgtsilvio.gradle.oci.internal.findExecutablePath
import io.github.sgtsilvio.gradle.oci.internal.gradle.redirectOutput
import io.github.sgtsilvio.gradle.oci.metadata.OciImageReference
import io.github.sgtsilvio.gradle.oci.platform.toPlatformArgument
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
        for ((_, imageReferences) in multiPlatformImageAndReferencesPairs) {
            for (imageReference in imageReferences) {
                val registryImageReference = "${getDockerHost()}:$registryPort/$imageReference"
                execOperations.exec {
                    executable = dockerExecutablePath
                    args = listOf("pull", registryImageReference)
                    if (singlePlatform != null) {
                        args("--platform", singlePlatform.toPlatformArgument())
                    }
                    redirectOutput(logger)
                }
                execOperations.exec {
                    executable = dockerExecutablePath
                    args = listOf("tag", registryImageReference, imageReference.toString())
                    redirectOutput(logger)
                }
                execOperations.exec {
                    executable = dockerExecutablePath
                    args = listOf("rmi", registryImageReference)
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
