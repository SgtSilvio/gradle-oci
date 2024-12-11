package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.image.OciRegistryDataTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.property
import org.gradle.process.CommandLineArgumentProvider

/**
 * @author Silvio Giebl
 */
internal class OciTestArgumentProvider(objectFactory: ObjectFactory) : CommandLineArgumentProvider {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val registryDataFiles = objectFactory.fileTree()

    private val registryDataDirectoryPath = objectFactory.property<String>()

    fun from(registryDataTask: TaskProvider<OciRegistryDataTask>) {
        val registryDataDirectory = registryDataTask.flatMap { it.registryDataDirectory }
        registryDataFiles.setDir(registryDataDirectory).setBuiltBy(listOf(registryDataTask))
        registryDataDirectoryPath.set(registryDataDirectory.map { it.asFile.absolutePath })
    }

    override fun asArguments() =
        listOf("-Dio.github.sgtsilvio.gradle.oci.registry.data.dir=${registryDataDirectoryPath.get()}")
}
