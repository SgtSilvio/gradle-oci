package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * @author Silvio Giebl
 */
internal class ConfigurationCacheTest {

    @Test
    fun configurationCacheReused(@TempDir projectDir: File) {
        val testProject = TestProject(projectDir)

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("test", "--configuration-cache", "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false")
            .build()

        assertTrue(result.output.contains("Configuration cache entry stored"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jarOciLayer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":ociComponent")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":testOciRegistryData")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        testProject.assertJarOciLayer()
        testProject.assertOciComponent()
        testProject.assertTagComponents()
        testProject.assertTestOciRegistryData()

        testProject.buildDir.deleteRecursively()

        val result2 = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("test", "--configuration-cache", "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false")
            .build()

        assertTrue(result2.output.contains("Configuration cache entry reused"))
        assertEquals(TaskOutcome.SUCCESS, result2.task(":jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result2.task(":jarOciLayer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result2.task(":ociComponent")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result2.task(":testOciRegistryData")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result2.task(":test")?.outcome)
        testProject.assertJarOciLayer()
        testProject.assertOciComponent()
        testProject.assertTagComponents()
        testProject.assertTestOciRegistryData()
    }
}
