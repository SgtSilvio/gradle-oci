package io.github.sgtsilvio.gradle.oci

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * @author Silvio Giebl
 */
interface OciPlatform {

    @get:Input
    val architecture: Property<String>

    @get:Input
    val os: Property<String>

    @get:Input
    @get:Optional
    val osVersion: Property<String>

    @get:Input
    @get:Optional
    val osFeatures: ListProperty<String>

    @get:Input
    @get:Optional
    val variant: Property<String>
}