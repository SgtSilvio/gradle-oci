package io.github.sgtsilvio.gradle.oci.internal

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories

internal fun Path.ensureEmptyDirectory(): Path {
    if (!toFile().deleteRecursively()) {
        throw IOException("$this could not be deleted")
    }
    return createDirectories()
}
