package io.github.sgtsilvio.gradle.oci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.util.*

/**
 * @author Silvio Giebl
 */
internal class TestProject(projectDir: File) {

    val buildDir = projectDir.resolve("build")

    init {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "test"
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                java
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
            testing {
                suites {
                    "test"(JvmTestSuite::class) {
                        useJUnitJupiter("5.10.0")
                        oci.of(this) {
                            imageDependencies {
                                runtime(project)
                                runtime(project).tag("latest")
                                runtime(constraint("library:eclipse-temurin:20.0.1_9-jre-jammy"))
                                runtime("hivemq:hivemq4:4.16.0")
                            }
                        }
                    }
                }
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
            """.trimIndent()
        )
        projectDir.resolve("src/main/java/test/Main.java").apply { parentFile.mkdirs() }.writeText(
            """
            package test;
            public class Main {
                public static void main(final String[] args) {
                    System.out.println("Hello world!");
                    System.out.println(System.getProperty("java.version"));
                }
            }
            """.trimIndent()
        )
        projectDir.resolve("src/test/java/test/ImageTest.java").apply { parentFile.mkdirs() }.writeText(
            """
            package test;
            import java.io.File;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class ImageTest {
                @Test
                void test() {
                    assertEquals(
                        new File("build/oci/registries/testSuite").getAbsolutePath(),
                        System.getProperty("io.github.sgtsilvio.gradle.oci.registry.data.dir")
                    );
                }
            }
            """.trimIndent()
        )
    }

    fun assertJarOciLayer(isBeforeGradle8: Boolean = false) {
        val imageDir = buildDir.resolve("oci/images/main")
        val propertiesFile = imageDir.resolve("jar-oci-layer.properties")
        val tarFile = imageDir.resolve("jar-oci-layer.tgz")
        assertTrue(propertiesFile.exists())
        assertTrue(tarFile.exists())
        val expectedDiffId: String
        val expectedDigest: String
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        if (isBeforeGradle8) {
            expectedDiffId = "sha256:f8363558d917871ea6722c762b6d4e67b0f2ac3be010ca94e4a74aead327212c"
            expectedDigest = "sha256:9f0241cf6e0f2ddad911248fbb4592b18c4dff4d69e1dffa03080acfe61bce6c"
        } else {
            expectedDiffId = "sha256:bf7023a316aaf2ae2ccd50dba4990f460cfbbd2b70ee08603c2e5452e48e0865"
            expectedDigest = "sha256:e6b88907d77d29e5dd75183b8c58e75d6abe195d0594c4b8b2282c4ce75a51f0"
        }
        assertEquals("digest=$expectedDigest\nsize=704\ndiffId=$expectedDiffId", propertiesFile.readText())
    }

    fun assertOciMetadata(isBeforeGradle8: Boolean = false) {
        val imageDir = buildDir.resolve("oci/images/main")
        val metadataJsonFile = imageDir.resolve("oci-metadata.json")
        assertTrue(metadataJsonFile.exists())
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        val expectedComponentJson = if (isBeforeGradle8) {
            """{"imageReference":"example/test:1.0.0","entryPoint":["java","-jar","app.jar"],"layers":[{"descriptor":{"digest":"sha256:9f0241cf6e0f2ddad911248fbb4592b18c4dff4d69e1dffa03080acfe61bce6c","size":704,"diffId":"sha256:f8363558d917871ea6722c762b6d4e67b0f2ac3be010ca94e4a74aead327212c"},"createdBy":"org.example:test:1.0.0 > jar (gradle-oci)"}]}"""
        } else {
            """{"imageReference":"example/test:1.0.0","entryPoint":["java","-jar","app.jar"],"layers":[{"descriptor":{"digest":"sha256:e6b88907d77d29e5dd75183b8c58e75d6abe195d0594c4b8b2282c4ce75a51f0","size":704,"diffId":"sha256:bf7023a316aaf2ae2ccd50dba4990f460cfbbd2b70ee08603c2e5452e48e0865"},"createdBy":"org.example:test:1.0.0 > jar (gradle-oci)"}]}"""
        }
        assertEquals(expectedComponentJson, metadataJsonFile.readText())
    }

    fun assertTestOciRegistryData(isBeforeGradle8: Boolean = false) {
        val expectedJarLayerDigest: String
        val expectedIndexDigest: String
        val expectedManifest1Digest: String
        val expectedManifest2Digest: String
        val expectedConfig1Digest: String
        val expectedConfig2Digest: String
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        if (isBeforeGradle8) {
            expectedJarLayerDigest = "9f0241cf6e0f2ddad911248fbb4592b18c4dff4d69e1dffa03080acfe61bce6c"
            expectedIndexDigest = "ea02bb723ac6f19148a8d8017779c89826efd7ba75de0f5233d2c203673337f3"
            expectedManifest1Digest = "070c5abaf9d1ad11fab4667cc4f1e8150ed2d018ca518ce7763b9e609144ef6e"
            expectedManifest2Digest = "24a72ef6ec7380501d9af7e2617053f278a96c4cc3c526b9f87ee699b2786579"
            expectedConfig1Digest = "a6dce28f03da28ecc378f4949280957222275fb48e6a21960c757009da540b8c"
            expectedConfig2Digest = "ae5a3213d366230fefeab8abd1cea574958b69b7fb50644e4fe4b9a4322f1c1a"
        } else {
            expectedJarLayerDigest = "e6b88907d77d29e5dd75183b8c58e75d6abe195d0594c4b8b2282c4ce75a51f0"
            expectedIndexDigest = "2f594a73b7f41a57a83f1fea3ddc2b0e90ca56bb9d1a69b867c6e710078d8ce3"
            expectedManifest1Digest = "fe3818b0f15d30239f7de4fd731de62b362841d22398995f4a5fa3f525f25fc1"
            expectedManifest2Digest = "77f63260e32aef383b5a05646dec77f75747ea5d3fe70ba8ec46fec806c099bc"
            expectedConfig1Digest = "4d8c8e84521e8f85cc3c7edc98c07fc3d6cefdaece779f7284a80830bf4595b9"
            expectedConfig2Digest = "e9740f11d43194a5dbd02fe12fcca1ab7cd577482b141a5d5e55d3d1ece18a27"
        }
        val testIndexAndManifestDigests = setOf(
            expectedManifest1Digest,
            expectedManifest2Digest,
            expectedIndexDigest,
        )
        val testConfigAndLayerDigests = setOf(
            "8b3654c299169c0f815629af51518c775817d09dd04da9a3bfa510cfa63f12bc",
            "9d19ee268e0d7bcf6716e6658ee1b0384a71d6f2f9aa1ae2085610cf7c7b316f",
            "92f85d89e7da0f3af0f527e7300cced784d0ba64249b8c376690599d313cb056",
            "3573c1225d462a8047deb540a10daffee48c949958f8b8842eaebe050daf48bb",
            "45953d25a53f34169be70bf8fc143e66c68cc46d47629b1b76b28be22647c9a9",
            "ac9c5946be2a99aca3f2643a8a98f230414662e4ea47e482ccad6bf6b0517657",
            "ac34a2e0269ced3acc355be706239ee0f3f1e73a035c40dd2fac74827164ee53",
            "f2b566cb887b5c06e04f5cd97660a99e73bd52ceb9d72c6db6383ae8470cc4cf",
            expectedJarLayerDigest,
            expectedConfig1Digest,
            expectedConfig2Digest,
        )
        val hivemqIndexDigest = "d498448faeaf83b9fa66defd14b2cadc168e211bcb78fb36c748c19b5580b699"
        val hivemqIndexAndManifestDigests = setOf(
            "c18c4e0236f2e8bec242432a19cff1d93bbd422b305e6900809fa4fcf0e07e48",
            hivemqIndexDigest,
        )
        val hivemqConfigAndLayerDigests = setOf(
            "1d511796a8d527cf68165c8b95d6606d03c6a30a624d781f8f3682ae14797078",
            "1efc276f4ff952c055dea726cfc96ec6a4fdb8b62d9eed816bd2b788f2860ad7",
            "3ba62f2fe51e3304e4c26340567f70a8dfbd4e0c608a2154e174575544aaa3d9",
            "3e37770490d98f2164cf29b50c7dc209a877d58182ae415540dc99d625e922b0",
            "4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1",
            "12cca292b13cb58fadde25af113ddc4ac3b0c5e39ab3f1290a6ba62ec8237afd",
            "18c6d4ab63429acdae46f5bc76e27378afaa44b40a746ae2465b3b4684846ef3",
            "33f5df018e7461c12a9942a1765d92002ac415ceb4cec48020e693c5b9022207",
            "66b08230f25a80ecfea7a229a734049fc0d9abe01f756f11f922d39357dc487f",
            "a2f2f93da48276873890ac821b3c991d53a7e864791aaf82c39b7863c908b93b",
            "d6ae466d10fc5d00afc56a152620df8477ecd22369053f2514d2ea38ad5ed1fb",
            "d73cf48caaac2e45ad76a2a9eb3b311d0e4eb1d804e3d2b9cf075a1fa31e6f92",
        )
        val blobDigests = setOf(
            *testIndexAndManifestDigests.toTypedArray(),
            *testConfigAndLayerDigests.toTypedArray(),
            *hivemqIndexAndManifestDigests.toTypedArray(),
            *hivemqConfigAndLayerDigests.toTypedArray(),
        )

        val registryDir = buildDir.resolve("oci/registries/testSuite")
        val blobsDir = registryDir.resolve("blobs")
        assertEquals(
            blobDigests.flatMapTo(TreeSet()) { digest ->
                listOf(
                    "sha256",
                    "sha256/${digest.substring(0, 2)}",
                    "sha256/${digest.substring(0, 2)}/$digest",
                    "sha256/${digest.substring(0, 2)}/$digest/data",
                )
            },
            blobsDir.walkTopDown().filter { it != blobsDir }.mapTo(TreeSet()) { it.toRelativeString(blobsDir) },
        )

        val repositoriesDir = registryDir.resolve("repositories")
        val testRepositoryDir = repositoriesDir.resolve("example/test")
        val testLayersDir = testRepositoryDir.resolve("_layers")
        // @formatter:off
        assertEquals(
            testConfigAndLayerDigests.flatMapTo(TreeSet()) { digest -> listOf("sha256", "sha256/$digest", "sha256/$digest/link") },
            testLayersDir.walkTopDown().filter { it != testLayersDir }.mapTo(TreeSet()) { it.toRelativeString(testLayersDir) },
        )
        // @formatter:on
        val testManifestsDir = testRepositoryDir.resolve("_manifests")
        val testManifestRevisionsDir = testManifestsDir.resolve("revisions")
        // @formatter:off
        assertEquals(
            testIndexAndManifestDigests.flatMapTo(TreeSet()) { digest -> listOf("sha256", "sha256/$digest", "sha256/$digest/link") },
            testManifestRevisionsDir.walkTopDown().filter { it != testManifestRevisionsDir }.mapTo(TreeSet()) { it.toRelativeString(testManifestRevisionsDir) },
        )
        // @formatter:on
        val testTagsDir = testManifestsDir.resolve("tags")
        val test1TagDir = testTagsDir.resolve("1.0.0")
        assertTrue(test1TagDir.resolve("current/link").exists())
        assertTrue(test1TagDir.resolve("index/sha256/$expectedIndexDigest/link").exists())
        val testLatestTagDir = testTagsDir.resolve("latest")
        assertTrue(testLatestTagDir.resolve("current/link").exists())
        assertTrue(testLatestTagDir.resolve("index/sha256/$expectedIndexDigest/link").exists())

        val hivemqRepositoryDir = repositoriesDir.resolve("hivemq/hivemq4")
        val hivemqLayersDir = hivemqRepositoryDir.resolve("_layers")
        // @formatter:off
        assertEquals(
            hivemqConfigAndLayerDigests.flatMapTo(TreeSet()) { digest -> listOf("sha256", "sha256/$digest", "sha256/$digest/link") },
            hivemqLayersDir.walkTopDown().filter { it != hivemqLayersDir }.mapTo(TreeSet()) { it.toRelativeString(hivemqLayersDir) },
        )
        // @formatter:on
        val hivemqManifestsDir = hivemqRepositoryDir.resolve("_manifests")
        val hivemqManifestRevisionsDir = hivemqManifestsDir.resolve("revisions")
        // @formatter:off
        assertEquals(
            hivemqIndexAndManifestDigests.flatMapTo(TreeSet()) { digest -> listOf("sha256", "sha256/$digest", "sha256/$digest/link") },
            hivemqManifestRevisionsDir.walkTopDown().filter { it != hivemqManifestRevisionsDir }.mapTo(TreeSet()) { it.toRelativeString(hivemqManifestRevisionsDir) },
        )
        // @formatter:on
        val hivemqTagDir = hivemqManifestsDir.resolve("tags/4.16.0")
        assertTrue(hivemqTagDir.resolve("current/link").exists())
        assertTrue(hivemqTagDir.resolve("index/sha256/$hivemqIndexDigest/link").exists())
    }
}
