package io.github.sgtsilvio.gradle.oci

import io.github.sgtsilvio.gradle.testkit.addArguments
import io.github.sgtsilvio.gradle.testkit.withJavaHome
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

/**
 * @author Silvio Giebl
 */
internal class GradleVersionsTest {

    @ParameterizedTest
    @CsvSource(
        value = [
            "8.3, 11", // minimum 8.x version
            "8.5, 11", // highest version for oldest ArtifactVisitor interface
            "8.6, 11", // lowest version for second oldest ArtifactVisitor interface
            "8.10.2, 11", // highest version not fulfilling version check >= 8.11 in VariantSelector and ResolutionResultExtensions
            "8.11, 11", // lowest version fulfilling version check >= 8.11 in VariantSelector and ResolutionResultExtensions
            "8.13, 11", // highest version for second oldest ArtifactVisitor interface
            "8.14, 11", // lowest version for third oldest ArtifactVisitor interface
            "9.0.0, 17", // highest version for third oldest ArtifactVisitor interface
            "9.1.0, 17", // lowest version for newest ArtifactVisitor interface
        ]
    )
    fun test(gradleVersion: String, javaVersion: String, @TempDir projectDir: File) {
        val testProject = TestProject(projectDir)

        val result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withJavaHome(System.getProperty("java.home.${javaVersion}"))
            .addArguments("test", "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jarOciLayer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":ociMetadata")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":testSuiteOciRegistryData")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        val isJava11 = javaVersion == "11"
        testProject.assertJarOciLayer(isJava11)
        testProject.assertOciMetadata(isJava11)
        testProject.assertTestOciRegistryData(isJava11)
    }
}
