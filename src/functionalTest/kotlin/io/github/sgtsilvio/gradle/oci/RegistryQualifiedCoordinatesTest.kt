package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class RegistryQualifiedCoordinatesTest {

    private fun setup(projectDir: File, imageDependency: String, registriesConfiguration: String) {
        projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "test"""")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.sgtsilvio.gradle.oci")
            }
            oci {
                registries {
                    $registriesConfiguration
                }
            }
            tasks.register("registryData", oci.registryDataTaskClass) {
                from(oci.imageDependencies.create("registryData") {
                    runtime("$imageDependency")
                })
                registryDataDirectory = layout.buildDirectory.dir("registryData")
            }
            """.trimIndent()
        )
    }

    @Test
    fun registryQualifiedCoordinatesResolveWithoutDeclaringTheirRegistry(@TempDir projectDir: File) {
        setup(
            projectDir,
            "registry-1.docker.io!library:eclipse-temurin:20.0.1_9-jre-jammy",
            """
            dockerHub {
                optionalCredentials()
            }
            """.trimIndent(),
        )

        val result =
            GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("registryData").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":registryData")?.outcome)
        assertTrue(projectDir.resolve("build/registryData/repositories/library/eclipse-temurin").isDirectory)
    }

    @Test
    fun registryQualifiedCoordinatesNeedARegistryOrTheSettingsPlugin(@TempDir projectDir: File) {
        setup(projectDir, "registry-1.docker.io!library:eclipse-temurin:20.0.1_9-jre-jammy", "")

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("registryData")
            .buildAndFail()

        assertTrue(
            result.output.contains(
                "Could not resolve registry-1.docker.io!library:eclipse-temurin:20.0.1_9-jre-jammy"
            )
        )
    }
}
