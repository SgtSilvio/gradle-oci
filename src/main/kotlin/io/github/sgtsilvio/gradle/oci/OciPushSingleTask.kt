package io.github.sgtsilvio.gradle.oci

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.setProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * @author Silvio Giebl
 */
@DisableCachingByDefault(because = "Pushing to an external registry")
abstract class OciPushSingleTask @Inject constructor(workerExecutor: WorkerExecutor) : OciPushTask(workerExecutor) {

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
