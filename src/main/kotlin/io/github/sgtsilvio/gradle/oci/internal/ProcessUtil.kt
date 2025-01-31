package io.github.sgtsilvio.gradle.oci.internal

import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal fun findExecutablePath(name: String): String {
    val pathEnvVar = System.getenv("PATH") ?: return name
    val searchPaths = pathEnvVar.split(File.pathSeparatorChar)
    for (searchPath in searchPaths) {
        val path = try {
            Path(searchPath, name)
        } catch (ignored: IllegalArgumentException) {
            continue
        }
        if (path.exists() && !path.isDirectory()) {
            return path.toString()
        }
    }
    return name
}
