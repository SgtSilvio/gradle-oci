package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.component.OciComponentResolver
import io.github.sgtsilvio.gradle.oci.component.decodeComponent
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*

/**
 * @author Silvio Giebl
 */
abstract class OciMetadataTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val componentFiles = project.objects.fileCollection()

    @get:OutputFile
    val digestToMetadataPropertiesFile = project.objects.fileProperty()

    @TaskAction
    protected fun run() {
        val ociComponentResolver = OciComponentResolver()
        for (file in componentFiles) {
            ociComponentResolver.addComponent(decodeComponent(file.readText()))
        }
        val platforms = ociComponentResolver.resolvePlatforms()
        for (platform in platforms) {
            val bundlesForPlatform = ociComponentResolver.collectBundlesForPlatform(platform)
            // TODO
        }
    }
}