package io.github.sgtsilvio.gradle.oci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

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
                        ociDependencies {
                            image(project)
                            image(project).tag("latest")
                            image(constraint("library:eclipse-temurin:20.0.1_9-jre-jammy"))
                            image("hivemq:hivemq4:4.16.0")
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
                        parentImages {
                            add("library:eclipse-temurin:17.0.7_7-jre-jammy")
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
                        new File("build/oci/registries/test").getAbsolutePath(),
                        System.getProperty("io.github.sgtsilvio.gradle.oci.registry.data.dir")
                    );
                }
            }
            """.trimIndent()
        )
    }

    fun assertJarOciLayer(isBeforeGradle8: Boolean = false) {
        val jarDir = buildDir.resolve("oci/images/main/jar")
        val diffIdFile = jarDir.resolve("jar-oci-layer.diffid")
        val digestFile = jarDir.resolve("jar-oci-layer.digest")
        val tarFile = jarDir.resolve("jar-oci-layer.tgz")
        assertTrue(diffIdFile.exists())
        assertTrue(digestFile.exists())
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
        assertEquals(expectedDiffId, diffIdFile.readText())
        assertEquals(expectedDigest, digestFile.readText())
    }

    fun assertOciComponent(isBeforeGradle8: Boolean = false) {
        val imageDir = buildDir.resolve("oci/images/main")
        val componentJsonFile = imageDir.resolve("oci-component.json")
        assertTrue(componentJsonFile.exists())
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        val expectedComponentJson = if (isBeforeGradle8) {
            """{"imageReference":"example/test:1.0.0","capabilities":[{"group":"org.example","name":"test","version":"1.0.0"}],"bundle":{"parentCapabilities":[{"group":"library","name":"eclipse-temurin"}],"command":{"entryPoint":["java","-jar","app.jar"],"arguments":[]},"layers":[{"descriptor":{"digest":"sha256:9f0241cf6e0f2ddad911248fbb4592b18c4dff4d69e1dffa03080acfe61bce6c","size":704,"diffId":"sha256:f8363558d917871ea6722c762b6d4e67b0f2ac3be010ca94e4a74aead327212c"},"createdBy":"gradle-oci: jar"}]}}"""
        } else {
            """{"imageReference":"example/test:1.0.0","capabilities":[{"group":"org.example","name":"test","version":"1.0.0"}],"bundle":{"parentCapabilities":[{"group":"library","name":"eclipse-temurin"}],"command":{"entryPoint":["java","-jar","app.jar"],"arguments":[]},"layers":[{"descriptor":{"digest":"sha256:e6b88907d77d29e5dd75183b8c58e75d6abe195d0594c4b8b2282c4ce75a51f0","size":704,"diffId":"sha256:bf7023a316aaf2ae2ccd50dba4990f460cfbbd2b70ee08603c2e5452e48e0865"},"createdBy":"gradle-oci: jar"}]}}"""
        }
        assertEquals(expectedComponentJson, componentJsonFile.readText())
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
            expectedIndexDigest = "581c698db9bf7fc21809df9290c06687a1d40f7d6e22338fd544fe7cfc2c2ff2"
            expectedManifest1Digest = "37bf3695f5995dd5ec95ea0583a3fd53c543962fc4372993f7a6d39055e9ed16"
            expectedManifest2Digest = "61cedfd321a6a234f0cfb0c97506497af54405de24e7d63f3b3cd61fffdaacec"
            expectedConfig1Digest = "383b193872227cbe7e5f85742c3b3d5e9fe2f1db99b25d007d9d2d8b5ed4f6ef"
            expectedConfig2Digest = "c880e34f4fba15aa3ae99e494c1d73db3dba8d09abdeec1102a641ad377451d6"
        } else {
            expectedJarLayerDigest = "e6b88907d77d29e5dd75183b8c58e75d6abe195d0594c4b8b2282c4ce75a51f0"
            expectedIndexDigest = "7f7843c962a8235352e0e11d331e6621a20634774d2e209f7f89a7b76e7857bc"
            expectedManifest1Digest = "10be4956d8ab1abc455e9a9260b67dcd2d6ca8febd42ea6049bab950a8a15edb"
            expectedManifest2Digest = "acd15ae115f4d1562c0ce83e8a628243c7b1130d90677b99ad9d29c95af39e0e"
            expectedConfig1Digest = "45c5e3d4ddbbd0b23a157b296cf09827dcd5b2bdb3323c58280948bd6955f8af"
            expectedConfig2Digest = "1013d54d227bc8e0ecdbc989217da3325065b8c0021123065b01bb15fd9eab04"
        }

        val registryDir = buildDir.resolve("oci/registries/test")
        val blobsDir = registryDir.resolve("blobs")
        // @formatter:off
        assertTrue(blobsDir.resolve("sha256/1d/1d511796a8d527cf68165c8b95d6606d03c6a30a624d781f8f3682ae14797078/data").exists())
        assertTrue(blobsDir.resolve("sha256/1e/1efc276f4ff952c055dea726cfc96ec6a4fdb8b62d9eed816bd2b788f2860ad7/data").exists())
        assertTrue(blobsDir.resolve("sha256/3b/3ba62f2fe51e3304e4c26340567f70a8dfbd4e0c608a2154e174575544aaa3d9/data").exists())
        assertTrue(blobsDir.resolve("sha256/3e/3e37770490d98f2164cf29b50c7dc209a877d58182ae415540dc99d625e922b0/data").exists())
        assertTrue(blobsDir.resolve("sha256/4f/4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1/data").exists())
        assertTrue(blobsDir.resolve("sha256/8b/8b3654c299169c0f815629af51518c775817d09dd04da9a3bfa510cfa63f12bc/data").exists())
        assertTrue(blobsDir.resolve("sha256/9d/9d19ee268e0d7bcf6716e6658ee1b0384a71d6f2f9aa1ae2085610cf7c7b316f/data").exists())
        assertTrue(blobsDir.resolve("sha256/12/12cca292b13cb58fadde25af113ddc4ac3b0c5e39ab3f1290a6ba62ec8237afd/data").exists())
        assertTrue(blobsDir.resolve("sha256/18/18c6d4ab63429acdae46f5bc76e27378afaa44b40a746ae2465b3b4684846ef3/data").exists())
        assertTrue(blobsDir.resolve("sha256/33/33f5df018e7461c12a9942a1765d92002ac415ceb4cec48020e693c5b9022207/data").exists())
        assertTrue(blobsDir.resolve("sha256/35/3573c1225d462a8047deb540a10daffee48c949958f8b8842eaebe050daf48bb/data").exists())
        assertTrue(blobsDir.resolve("sha256/45/45953d25a53f34169be70bf8fc143e66c68cc46d47629b1b76b28be22647c9a9/data").exists())
        assertTrue(blobsDir.resolve("sha256/66/66b08230f25a80ecfea7a229a734049fc0d9abe01f756f11f922d39357dc487f/data").exists())
        assertTrue(blobsDir.resolve("sha256/92/92f85d89e7da0f3af0f527e7300cced784d0ba64249b8c376690599d313cb056/data").exists())
        assertTrue(blobsDir.resolve("sha256/a2/a2f2f93da48276873890ac821b3c991d53a7e864791aaf82c39b7863c908b93b/data").exists())
        assertTrue(blobsDir.resolve("sha256/ac/ac9c5946be2a99aca3f2643a8a98f230414662e4ea47e482ccad6bf6b0517657/data").exists())
        assertTrue(blobsDir.resolve("sha256/ac/ac34a2e0269ced3acc355be706239ee0f3f1e73a035c40dd2fac74827164ee53/data").exists())
        assertTrue(blobsDir.resolve("sha256/c1/c18c4e0236f2e8bec242432a19cff1d93bbd422b305e6900809fa4fcf0e07e48/data").exists())
        assertTrue(blobsDir.resolve("sha256/d4/d498448faeaf83b9fa66defd14b2cadc168e211bcb78fb36c748c19b5580b699/data").exists())
        assertTrue(blobsDir.resolve("sha256/d6/d6ae466d10fc5d00afc56a152620df8477ecd22369053f2514d2ea38ad5ed1fb/data").exists())
        assertTrue(blobsDir.resolve("sha256/d7/d73cf48caaac2e45ad76a2a9eb3b311d0e4eb1d804e3d2b9cf075a1fa31e6f92/data").exists())
        assertTrue(blobsDir.resolve("sha256/f2/f2b566cb887b5c06e04f5cd97660a99e73bd52ceb9d72c6db6383ae8470cc4cf/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedJarLayerDigest.substring(0, 2)}/$expectedJarLayerDigest/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedConfig1Digest.substring(0, 2)}/$expectedConfig1Digest/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedConfig2Digest.substring(0, 2)}/$expectedConfig2Digest/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedManifest1Digest.substring(0, 2)}/$expectedManifest1Digest/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedManifest2Digest.substring(0, 2)}/$expectedManifest2Digest/data").exists())
        assertTrue(blobsDir.resolve("sha256/${expectedIndexDigest.substring(0, 2)}/$expectedIndexDigest/data").exists())
        // @formatter:on

        val repositoriesDir = registryDir.resolve("repositories")
        val testRepositoryDir = repositoriesDir.resolve("example/test")
        val testLayersDir = testRepositoryDir.resolve("_layers")
        // @formatter:off
        assertTrue(testLayersDir.resolve("sha256/8b3654c299169c0f815629af51518c775817d09dd04da9a3bfa510cfa63f12bc/link").exists())
        assertTrue(testLayersDir.resolve("sha256/9d19ee268e0d7bcf6716e6658ee1b0384a71d6f2f9aa1ae2085610cf7c7b316f/link").exists())
        assertTrue(testLayersDir.resolve("sha256/92f85d89e7da0f3af0f527e7300cced784d0ba64249b8c376690599d313cb056/link").exists())
        assertTrue(testLayersDir.resolve("sha256/3573c1225d462a8047deb540a10daffee48c949958f8b8842eaebe050daf48bb/link").exists())
        assertTrue(testLayersDir.resolve("sha256/45953d25a53f34169be70bf8fc143e66c68cc46d47629b1b76b28be22647c9a9/link").exists())
        assertTrue(testLayersDir.resolve("sha256/ac9c5946be2a99aca3f2643a8a98f230414662e4ea47e482ccad6bf6b0517657/link").exists())
        assertTrue(testLayersDir.resolve("sha256/ac34a2e0269ced3acc355be706239ee0f3f1e73a035c40dd2fac74827164ee53/link").exists())
        assertTrue(testLayersDir.resolve("sha256/f2b566cb887b5c06e04f5cd97660a99e73bd52ceb9d72c6db6383ae8470cc4cf/link").exists())
        assertTrue(testLayersDir.resolve("sha256/$expectedJarLayerDigest/link").exists())
        assertTrue(testLayersDir.resolve("sha256/$expectedConfig1Digest/link").exists())
        assertTrue(testLayersDir.resolve("sha256/$expectedConfig2Digest/link").exists())
        // @formatter:on
        val testManifestsDir = testRepositoryDir.resolve("_manifests")
        val testManifestRevisionsDir = testManifestsDir.resolve("revisions")
        // @formatter:off
        assertTrue(testManifestRevisionsDir.resolve("sha256/$expectedIndexDigest/link").exists())
        assertTrue(testManifestRevisionsDir.resolve("sha256/$expectedManifest1Digest/link").exists())
        assertTrue(testManifestRevisionsDir.resolve("sha256/$expectedManifest2Digest/link").exists())
        // @formatter:on
        val testTagsDir = testManifestsDir.resolve("tags")
        val test1TagDir = testTagsDir.resolve("1.0.0")
        // @formatter:off
        assertTrue(test1TagDir.resolve("current/link").exists())
        assertTrue(test1TagDir.resolve("index/sha256/$expectedIndexDigest/link").exists())
        // @formatter:on
        val testLatestTagDir = testTagsDir.resolve("latest")
        // @formatter:off
        assertTrue(testLatestTagDir.resolve("current/link").exists())
        assertTrue(testLatestTagDir.resolve("index/sha256/$expectedIndexDigest/link").exists())
        // @formatter:on

        val hivemqRepositoryDir = repositoriesDir.resolve("hivemq/hivemq4")
        val hivemqLayersDir = hivemqRepositoryDir.resolve("_layers")
        // @formatter:off
        assertTrue(hivemqLayersDir.resolve("sha256/1d511796a8d527cf68165c8b95d6606d03c6a30a624d781f8f3682ae14797078/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/1efc276f4ff952c055dea726cfc96ec6a4fdb8b62d9eed816bd2b788f2860ad7/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/3ba62f2fe51e3304e4c26340567f70a8dfbd4e0c608a2154e174575544aaa3d9/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/3e37770490d98f2164cf29b50c7dc209a877d58182ae415540dc99d625e922b0/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/12cca292b13cb58fadde25af113ddc4ac3b0c5e39ab3f1290a6ba62ec8237afd/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/18c6d4ab63429acdae46f5bc76e27378afaa44b40a746ae2465b3b4684846ef3/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/33f5df018e7461c12a9942a1765d92002ac415ceb4cec48020e693c5b9022207/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/66b08230f25a80ecfea7a229a734049fc0d9abe01f756f11f922d39357dc487f/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/a2f2f93da48276873890ac821b3c991d53a7e864791aaf82c39b7863c908b93b/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/d6ae466d10fc5d00afc56a152620df8477ecd22369053f2514d2ea38ad5ed1fb/link").exists())
        assertTrue(hivemqLayersDir.resolve("sha256/d73cf48caaac2e45ad76a2a9eb3b311d0e4eb1d804e3d2b9cf075a1fa31e6f92/link").exists())
        // @formatter:on
        val hivemqManifestsDir = hivemqRepositoryDir.resolve("_manifests")
        val hivemqManifestRevisionsDir = hivemqManifestsDir.resolve("revisions")
        // @formatter:off
        assertTrue(hivemqManifestRevisionsDir.resolve("sha256/c18c4e0236f2e8bec242432a19cff1d93bbd422b305e6900809fa4fcf0e07e48/link").exists())
        assertTrue(hivemqManifestRevisionsDir.resolve("sha256/d498448faeaf83b9fa66defd14b2cadc168e211bcb78fb36c748c19b5580b699/link").exists())
        // @formatter:on
        val hivemqTagDir = hivemqManifestsDir.resolve("tags/4.16.0")
        // @formatter:off
        assertTrue(hivemqTagDir.resolve("current/link").exists())
        assertTrue(hivemqTagDir.resolve("index/sha256/d498448faeaf83b9fa66defd14b2cadc168e211bcb78fb36c748c19b5580b699/link").exists())
        // @formatter:on
    }
}
