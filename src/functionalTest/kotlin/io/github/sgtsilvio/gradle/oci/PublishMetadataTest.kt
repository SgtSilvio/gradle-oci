package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * @author Silvio Giebl
 */
internal class PublishMetadataTest {

    @Test
    fun test(@TempDir projectDir: File) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
                `maven-publish`
                id("io.github.sgtsilvio.gradle.oci")
            }
            group = "org.example"
            version = "1.0.0"
            java {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(11))
                }
            }
            tasks.jar {
                manifest.attributes("Main-Class" to "org.example.oci.demo.Main")
            }
            tasks.withType<AbstractArchiveTask>().configureEach {
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }
            repositories {
                mavenCentral()
            }
            oci {
                registries {
                    dockerHub {
                        optionalCredentials()
                    }
                }
                imageDefinitions.register("main") {
                    allPlatforms {
                        dependencies {
                            runtime("library:eclipse-temurin:17.0.7_7-jre-jammy")
                        }
                        config {
                            entryPoint.set(listOf("java", "-jar", "app.jar"))
                        }
                        layer("jar") {
                            contents {
                                from(tasks.jar)
                                rename(".*", "app.jar")
                            }
                        }
                    }
                }
            }
            val javaComponent = components["java"] as AdhocComponentWithVariants
            configurations.all {
                if (name.startsWith("ociImage")) {
                    javaComponent.addVariantsFromConfiguration(this) {}
                }
            }
            publishing {
                publications {
                    register<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("src/main/java/test/Main.java").apply { parentFile.mkdirs() }.writeText(
            """
            package test;
            public class Main {
                public static void main(final String[] args) {
                    System.out.println("Hello world!");
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("generateMetadataFileForMavenPublication", "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":jar")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jarOciLayer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":ociMetadata")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateMetadataFileForMavenPublication")?.outcome)

        val moduleMetadata = JSONObject(projectDir.resolve("build/publications/maven/module.json").readText())
        val ociImageVariant = moduleMetadata.getJSONArray("variants")
            .filterIsInstance<JSONObject>()
            .find { it.getString("name") == "ociImage" } ?: fail()
        val expectedOciImageVariant = JSONObject(
            """
            {
              "name": "ociImage",
              "attributes": {
                "io.github.sgtsilvio.gradle.distributiontype": "oci-image",
                "org.gradle.category": "distribution",
                "org.gradle.dependency.bundling": "external"
              },
              "dependencies": [
                {
                  "group": "library",
                  "module": "eclipse-temurin",
                  "version": {"requires": "17.0.7_7-jre-jammy"}
                }
              ],
              "files": [
                {
                  "name": "test-1.0.0-oci-metadata.json",
                  "url": "test-1.0.0-oci-metadata.json",
                  "size": 339,
                  "sha512": "738742622bf75e3b75097474130f4846f54772cd6cc252ec9b17678da207402361ac76a08c1db5c99adc3dbbc09ce5e9489187ba0fdf02246cd967f373fdbb76",
                  "sha256": "4945711ce16987c3b39988e256d3586f400eedb859a9c9ad4a7133604f563f23",
                  "sha1": "1d29de1a48c874b1531c1f06363468d76591d68c",
                  "md5": "d240ee9b52653a06eb823310160e20bf"
                },
                {
                  "name": "test-1.0.0-jar-oci-layer.tgz",
                  "url": "test-1.0.0-jar-oci-layer.tgz",
                  "size": 658,
                  "sha512": "40c2513c227a48183b1d67b42ecd50b1442f28966417cb7aa25c276aef13fdc83466289b2cdf7261c8bfc916e8f6c3a992c10013cde3c2f6a03dd66ce8e603dd",
                  "sha256": "8414ba9a8ba8aae337344235efcdd0da17543cbccb6e7e963e01922077c8f6d6",
                  "sha1": "d34dfe6bdd295cdc2b3b7a1f0f22eaf734d899a6",
                  "md5": "ba7649eb297ff6177e909dd9a83ee21b"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(expectedOciImageVariant.toMap(), ociImageVariant.toMap())
    }
}
