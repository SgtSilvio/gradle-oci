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
        project.extensions.create(OciExtension::class, EXTENSION_NAME, OciExtensionImpl::class)
    }
}

const val EXTENSION_NAME = "oci"
const val TASK_GROUP_NAME = "oci"