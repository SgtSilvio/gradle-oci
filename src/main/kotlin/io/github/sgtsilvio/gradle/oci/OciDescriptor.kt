package io.github.sgtsilvio.gradle.oci

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * @author Silvio Giebl
 */
interface OciDescriptor {

    @get:Input
    val digest: Property<String>

    @get:Input
    val size: Property<Long>

    @get:Input
    @get:Optional
    val urls: ListProperty<String>

    @get:Input
    @get:Optional
    val annotations: MapProperty<String, String>

    @get:Input
    @get:Optional
    val data: Property<String>
}