package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.oci.dsl.OciExtension
import io.github.sgtsilvio.gradle.oci.internal.dsl.OciExtensionImpl
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

/**
 * @author Silvio Giebl
 */
class OciPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ociExtension = project.extensions.create(OciExtension::class, "oci", OciExtensionImpl::class)

        // test task extension:
        // create resolvable configuration via extension
        // maybe provide dsl around
        // dsl allows creation of separate or combined (multiple images) configurations
        // dsl allows attaching to test tasks, multiple configurations need to be attachable to a test task to allow separate configurations
        // jvmArgumentProvider => system properties via -Dname=value

        /*
        for each image dependency register MetadataTask and LayerDigestsTask
        jvmArgumentProvider CommandLineArgumentProvider
        - all files of the configuration(s) that are not json files (layers) as input files
        - all MetadataTask.digestToMetadataPropertiesFile as input files
        - only depend on LayerDigestsTask (Test.dependsOn) because digestToLayerPathPropertiesFile is transformed into system properties (so that test distribution replaces the paths
          => does not work because then the paths are not an input to the Test task which is wrong => really??? => no
        - all LayerDigestTask.digestToLayerPathPropertiesFile.map { properties to map } as map input property => internal, not input, only dependsOn
        - asArguments returns system properties for each map entry "-Dkey=value"
        - maybe add optional system property "-Dio.github.sgtsilvio.gradle.oci.property.prefix=value"

        configuration name: name + OciImageDependencies
         */
    }
}