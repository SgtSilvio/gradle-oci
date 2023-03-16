package io.github.sgtsilvio.gradle.oci.internal.dsl

import io.github.sgtsilvio.gradle.oci.dsl.OciTaskExtension
import org.gradle.api.NonExtensible
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.JavaForkOptions

/**
 * @author Silvio Giebl
 */
abstract class OciTaskExtensionImpl : OciTaskExtension {

    fun <T> applyTo(task: T) where T: Task, T: JavaForkOptions {
//        task.extensions.create()
        task.jvmArgumentProviders += ArgumentProvider()
    }

    @NonExtensible // TODO maybe move inputs to task extension, so this does not need to be created by object factory
    class ArgumentProvider: CommandLineArgumentProvider {
//        @get:InputFiles
//        @get:PathSensitive(PathSensitivity.NONE)
//        val layerFiles: ConfigurableFileCollection = ;
//
//        @get:InputFiles
//        @get:PathSensitive(PathSensitivity.NONE)
//        val digestToMetadataPropertiesFiles: ConfigurableFileCollection = ;
//
//        @get:Internal
//        val digestToLayerPathPropertiesFiles: ConfigurableFileCollection = ;

        override fun asArguments(): MutableIterable<String> {
            TODO("Not yet implemented")
        }
    }
}