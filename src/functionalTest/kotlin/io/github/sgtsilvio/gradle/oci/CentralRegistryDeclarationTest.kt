package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.*

/**
 * @author Silvio Giebl
 */
internal class CentralRegistryDeclarationTest {

    private fun setup(projectDir: File, settingsConfiguration: String, projectConfiguration: String) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            plugins {
                id("io.github.sgtsilvio.gradle.oci")
            }
            rootProject.name = "test"
            
            """.trimIndent() + settingsConfiguration
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.sgtsilvio.gradle.oci")
            }
            tasks.register("registryData", oci.registryDataTaskClass) {
                from(oci.imageDependencies.create("registryData") {
                    runtime("library:eclipse-temurin:20.0.1_9-jre-jammy")
                })
                registryDataDirectory = layout.buildDirectory.dir("registryData")
            }
            
            """.trimIndent() + projectConfiguration
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["PREFER_PROJECT", "PREFER_SETTINGS", "FAIL_ON_PROJECT_REPOS"])
    fun onlyCentralRegistryDeclaration(repositoryMode: String, @TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.$repositoryMode
                oci {
                    registries {
                        dockerHub {
                            optionalCredentials()
                        }
                    }
                }
            }
            """.trimIndent(),
            "",
        )
        val result =
            GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("registryData").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":registryData")?.outcome)
        assertRegistryData(projectDir)
    }

    @Test
    fun preferProjectRegistryDeclaration(@TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_PROJECT
                oci {
                    registries {
                        gitHubContainerRegistry {}
                    }
                }
            }
            """.trimIndent(),
            """
            oci {
                registries {
                    dockerHub {
                        optionalCredentials()
                    }
                }
            }
            """.trimIndent(),
        )
        val result =
            GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("registryData").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":registryData")?.outcome)
        assertRegistryData(projectDir)
    }

    @Test
    fun preferIncorrectProjectRegistryDeclaration(@TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_PROJECT
                oci {
                    registries {
                        dockerHub {
                            optionalCredentials()
                        }
                    }
                }
            }
            """.trimIndent(),
            """
            oci {
                registries {
                    gitHubContainerRegistry {}
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("registryData")
            .buildAndFail()
        assertTrue(result.output.contains("Could not resolve library:eclipse-temurin:20.0.1_9-jre-jammy"))
    }

    @Test
    fun preferSettingsRegistryDeclaration(@TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_SETTINGS
                oci {
                    registries {
                        dockerHub {
                            optionalCredentials()
                        }
                    }
                }
            }
            """.trimIndent(),
            """
            oci {
                registries {
                    gitHubContainerRegistry {}
                }
            }
            """.trimIndent(),
        )
        val result =
            GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments("registryData").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":registryData")?.outcome)
        assertRegistryData(projectDir)
    }

    @Test
    fun preferIncorrectSettingsRegistryDeclaration(@TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.PREFER_SETTINGS
                oci {
                    registries {
                        gitHubContainerRegistry {}
                    }
                }
            }
            """.trimIndent(),
            """
            oci {
                registries {
                    dockerHub {
                        optionalCredentials()
                    }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("registryData")
            .buildAndFail()
        assertTrue(result.output.contains("Could not resolve library:eclipse-temurin:20.0.1_9-jre-jammy"))
    }

    @Test
    fun failOnProjectRegistryDeclaration(@TempDir projectDir: File) {
        setup(
            projectDir,
            """
            dependencyResolutionManagement {
                repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
                oci {
                    registries {
                        dockerHub {
                            optionalCredentials()
                        }
                    }
                }
            }
            """.trimIndent(),
            """
            oci {
                registries {
                    dockerHub {
                        optionalCredentials()
                    }
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("registryData")
            .buildAndFail()
        assertTrue(result.output.contains("Build was configured to prefer settings repositories over project repositories but repository 'dockerHubOciRegistry' was added by build file 'build.gradle.kts'"))
    }

    private fun assertRegistryData(projectDir: File) {
        val temurinIndexDigest = "421c49b877b4046a36746a115c175f18f2f4b21f032233d09582caa95b00c573"
        val temurinManifest1Digest = "acd342bf5fa1f563d0f27e0ed522dee4f69cb7c3ba23ef49f9b200d27d8f27fb"
        val temurinManifest2Digest = "c614d9f48555f4bdcbf9ee521fd258da26c889f956c812624dd982da68a8b601"
        val temurinConfigAndLayerDigests = setOf(
            "92f85d89e7da0f3af0f527e7300cced784d0ba64249b8c376690599d313cb056",
            "3573c1225d462a8047deb540a10daffee48c949958f8b8842eaebe050daf48bb",
            "9d19ee268e0d7bcf6716e6658ee1b0384a71d6f2f9aa1ae2085610cf7c7b316f",
            "ac34a2e0269ced3acc355be706239ee0f3f1e73a035c40dd2fac74827164ee53",
            "ac9c5946be2a99aca3f2643a8a98f230414662e4ea47e482ccad6bf6b0517657",
            "e285283a7653e8a9a15ed996cfa4687247b7550d7f2db630153b463f4fac9538",
            "f2b566cb887b5c06e04f5cd97660a99e73bd52ceb9d72c6db6383ae8470cc4cf",
            "45953d25a53f34169be70bf8fc143e66c68cc46d47629b1b76b28be22647c9a9",
            "bfd9b4317a9b2c822f3aa111a11e4de14b5b0a8330a2a02dbf74be289eb86472",
            "8b3654c299169c0f815629af51518c775817d09dd04da9a3bfa510cfa63f12bc",
        )
        val blobDigests = setOf(
            temurinIndexDigest,
            temurinManifest1Digest,
            temurinManifest2Digest,
            *temurinConfigAndLayerDigests.toTypedArray(),
        )
        val registryDir = projectDir.resolve("build/registryData")
        assertEquals(
            sortedSetOf(
                *blobDigests.map { "blobs/sha256/${it.substring(0, 2)}/$it/data" }.toTypedArray(),
                *temurinConfigAndLayerDigests.map { "repositories/library/eclipse-temurin/_layers/sha256/$it/link" }
                    .toTypedArray(),
                "repositories/library/eclipse-temurin/_manifests/revisions/sha256/$temurinIndexDigest/link",
                "repositories/library/eclipse-temurin/_manifests/revisions/sha256/$temurinManifest1Digest/link",
                "repositories/library/eclipse-temurin/_manifests/revisions/sha256/$temurinManifest2Digest/link",
                "repositories/library/eclipse-temurin/_manifests/tags/20.0.1_9-jre-jammy/current/link",
                "repositories/library/eclipse-temurin/_manifests/tags/20.0.1_9-jre-jammy/index/sha256/$temurinIndexDigest/link",
            ),
            registryDir.leaves.mapTo(TreeSet()) { it.toRelativeString(registryDir) },
        )
    }
}
