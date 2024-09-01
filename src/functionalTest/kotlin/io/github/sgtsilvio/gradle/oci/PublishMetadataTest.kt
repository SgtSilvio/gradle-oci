package io.github.sgtsilvio.gradle.oci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
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
                        layers {
                            layer("jar") {
                                contents {
                                    from(tasks.jar)
                                    rename(".*", "app.jar")
                                }
                            }
                        }
                    }
                }
            }
            val javaComponent = components["java"] as AdhocComponentWithVariants
            javaComponent.addVariantsFromConfiguration(configurations["ociImage"]) {}
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
                "io.github.sgtsilvio.gradle.platform": "universal",
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
                  "size": 313,
                  "sha512": "746de5c093ac73f3b6b693f0d2e03c608d54d341eeaa57176b6b5f24653dc277b2f65cdb2912b9abdeaec49092265c0faf4aba44b9c152e7015a8a7719fd9ac9",
                  "sha256": "3ad109eb16bdf9819d61d2e6a279041de00f063689726491d1325a58249efa90",
                  "sha1": "21ed0e46e81711a45f1788274e1da164a42d9e94",
                  "md5": "e6e8f26fe56934656689bc03eef083e1"
                },
                {
                  "name": "test-1.0.0-jar-oci-layer.tgz",
                  "url": "test-1.0.0-jar-oci-layer.tgz",
                  "size": 658,
                  "sha512": "198f27beef9682ed4e060e7ef9f1f6d5bb040e335e4f88dc42a2d2ee91326203ca6d9663e61f005cdd6d0d42e227b40de64f6627b5d2b07e9ec31947e812a99d",
                  "sha256": "ff7671a164ce921dc63d32e0af3c9a5aaafee2fa4d8f09565468c471a09a2426",
                  "sha1": "cc042eabe35afed7a80bf058ca8b76576fb3aa1b",
                  "md5": "67b04445855a3e4a935bd6af5bb733aa"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(expectedOciImageVariant.toMap(), ociImageVariant.toMap())
    }
}
