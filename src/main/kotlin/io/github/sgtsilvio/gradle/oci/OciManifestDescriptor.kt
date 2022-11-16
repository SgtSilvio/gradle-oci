package io.github.sgtsilvio.gradle.oci

import org.gradle.api.Action
import org.gradle.api.tasks.Nested

/**
 * @author Silvio Giebl
 */
interface OciManifestDescriptor : OciDescriptor {

    @get:Nested
    val platform: OciPlatform

    fun platform(action: Action<in OciPlatform>) = action.execute(platform)
}