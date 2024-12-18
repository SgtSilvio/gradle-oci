package io.github.sgtsilvio.gradle.oci.internal.gradle

import io.github.sgtsilvio.gradle.oci.internal.string.LineOutputStream
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.process.BaseExecSpec

internal fun LineOutputStream(logger: Logger, logLevel: LogLevel) = LineOutputStream { logger.log(logLevel, it) }

internal fun BaseExecSpec.redirectOutput(logger: Logger) {
    standardOutput = LineOutputStream(logger, LogLevel.INFO)
    errorOutput = LineOutputStream(logger, LogLevel.ERROR)
}
