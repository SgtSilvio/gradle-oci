package io.github.sgtsilvio.gradle.oci.internal

import org.apache.commons.lang3.SystemUtils
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal fun findExecutablePath(name: String): String {
    if (SystemUtils.IS_OS_UNIX) {
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
    }
    return name
}
