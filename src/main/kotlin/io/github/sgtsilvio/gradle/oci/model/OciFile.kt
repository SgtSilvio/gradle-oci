package io.github.sgtsilvio.gradle.oci.model

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

/**
 * @author Silvio Giebl
 */
interface OciFile {
    val file: Provider<RegularFile>
    val digest: Provider<String>
}