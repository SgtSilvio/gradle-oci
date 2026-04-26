package io.github.sgtsilvio.gradle.oci.dsl

import io.github.sgtsilvio.gradle.oci.mapping.OciImageMapping
import org.gradle.api.Action

/**
 * @author Silvio Giebl
 */
interface OciSettingsExtension {
    val registries: OciRegistries
    val imageMapping: OciImageMapping

    fun registries(configuration: Action<in OciRegistries>)

    fun imageMapping(configuration: Action<in OciImageMapping>)
}