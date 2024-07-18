package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * @author Silvio Giebl
 */
internal class GradleVersionsTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "7.4", // minimum version
            "8.5", // highest version not fulfilling version check >= 8.6 in OciImageDefinitionImpl
            "8.6", // lowest version fulfilling version check >= 8.6 in OciImageDefinitionImpl
        ]
    )
    fun test(gradleVersion: String, @TempDir projectDir: File) {
        val testProject = TestProject(projectDir)

        val result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("test", "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jarOciLayer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":ociMetadata")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":testOciRegistryData")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val isBeforeGradle8 = gradleVersion.startsWith('7')
        testProject.assertJarOciLayer(isBeforeGradle8)
        testProject.assertOciMetadata(isBeforeGradle8)
        testProject.assertTestOciRegistryData(isBeforeGradle8)
    }
}
