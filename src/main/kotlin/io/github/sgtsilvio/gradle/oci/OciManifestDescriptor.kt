package io.github.sgtsilvio.gradle.oci

import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional

/**
 * @author Silvio Giebl
 */
interface OciManifestDescriptor : OciDescriptor {

    @get:Nested
    @get:Optional
    val platform: OciPlatform
}