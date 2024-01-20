package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.DistributionRegistryDataTask
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.CommandLineArgumentProvider

/**
 * @author Silvio Giebl
 */
internal class OciTestArgumentProvider(
    objectFactory: ObjectFactory,
    registryDataTask: TaskProvider<DistributionRegistryDataTask>,
) : CommandLineArgumentProvider {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val registryDataFiles: FileTree

    private val registryDataDirectoryPath: Provider<String>

    init {
        val registryDataDirectory = registryDataTask.flatMap { it.registryDataDirectory }
        registryDataFiles = objectFactory.fileTree().from(registryDataDirectory).builtBy(registryDataTask)
        registryDataDirectoryPath = registryDataDirectory.map { it.asFile.absolutePath }
    }

    override fun asArguments() =
        listOf("-Dio.github.sgtsilvio.gradle.oci.registry.data.dir=${registryDataDirectoryPath.get()}")
}
