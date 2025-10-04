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
                                runtime("library:redis:sha256!0779069b3c24a47a2f681855c1c01d046793e7c5f7d2b079c2aa0652c42eaf0e").tag("8.0.0-alpine")
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
                        layer("jar") {
                            contents {
                                from(tasks.jar)
                                rename(".*", "app.jar")
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
            expectedDigest = "sha256:20161c668faaf4435aa0433a7d0c56c6a8049dcca1fa10ec0163c5cbb39c5906"
        } else {
            expectedDiffId = "sha256:bf7023a316aaf2ae2ccd50dba4990f460cfbbd2b70ee08603c2e5452e48e0865"
            expectedDigest = "sha256:f40641a27c4a1940507aec41054f0320570667bfc840788511af53e79ee303f1"
        }
        assertEquals("digest=$expectedDigest\nsize=704\ndiffId=$expectedDiffId", propertiesFile.readText())
    }

    fun assertOciMetadata(isBeforeGradle8: Boolean = false) {
        val imageDir = buildDir.resolve("oci/images/main")
        val metadataJsonFile = imageDir.resolve("oci-metadata.json")
        assertTrue(metadataJsonFile.exists())
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        val expectedComponentJson = if (isBeforeGradle8) {
            """{"imageReference":"example/test:1.0.0","entryPoint":["java","-jar","app.jar"],"layers":[{"descriptor":{"digest":"sha256:20161c668faaf4435aa0433a7d0c56c6a8049dcca1fa10ec0163c5cbb39c5906","size":704,"diffId":"sha256:f8363558d917871ea6722c762b6d4e67b0f2ac3be010ca94e4a74aead327212c"},"createdBy":"org.example:test:1.0.0 > jar (gradle-oci)"}]}"""
        } else {
            """{"imageReference":"example/test:1.0.0","entryPoint":["java","-jar","app.jar"],"layers":[{"descriptor":{"digest":"sha256:f40641a27c4a1940507aec41054f0320570667bfc840788511af53e79ee303f1","size":704,"diffId":"sha256:bf7023a316aaf2ae2ccd50dba4990f460cfbbd2b70ee08603c2e5452e48e0865"},"createdBy":"org.example:test:1.0.0 > jar (gradle-oci)"}]}"""
        }
        assertEquals(expectedComponentJson, metadataJsonFile.readText())
    }

    fun assertTestOciRegistryData(isBeforeGradle8: Boolean = false) {
        val testJarLayerDigest: String
        val testIndexDigest: String
        val testManifest1Digest: String
        val testManifest2Digest: String
        val testConfig1Digest: String
        val testConfig2Digest: String
        // https://docs.gradle.org/current/userguide/upgrading_version_7.html#reproducible_archives_can_change_compared_to_past_versions
        if (isBeforeGradle8) {
            testJarLayerDigest = "20161c668faaf4435aa0433a7d0c56c6a8049dcca1fa10ec0163c5cbb39c5906"
            testIndexDigest = "ce90b56896cd021ab9d372afbef183ef6547ee978ef4e28f7cab651476e6c5a3"
            testManifest1Digest = "3dd8f076dcab7b1d2701072f8df3f54f4d429e07a9f111f91b210a50cf18d78e"
            testManifest2Digest = "af34253e212ba97e31ae739414e96e71e96b4b35f73baa40874cee579f7f223f"
            testConfig1Digest = "a6dce28f03da28ecc378f4949280957222275fb48e6a21960c757009da540b8c"
            testConfig2Digest = "ae5a3213d366230fefeab8abd1cea574958b69b7fb50644e4fe4b9a4322f1c1a"
        } else {
            testJarLayerDigest = "f40641a27c4a1940507aec41054f0320570667bfc840788511af53e79ee303f1"
            testIndexDigest = "4cfdfd9e071b46abfa348bdf30f9956d6be7b8d5c1114366ff2ac0629c10678c"
            testManifest1Digest = "336829a26c3be69a4a1d066adb9e25258822c6b0fe1008070b9f711e9b3f6287"
            testManifest2Digest = "3c38b025e936128c8fe87bf0e0dcdc1a2ba1c67295feb59600b424ee2702d219"
            testConfig1Digest = "4d8c8e84521e8f85cc3c7edc98c07fc3d6cefdaece779f7284a80830bf4595b9"
            testConfig2Digest = "e9740f11d43194a5dbd02fe12fcca1ab7cd577482b141a5d5e55d3d1ece18a27"
        }
        val testConfigAndLayerDigests = setOf(
            "8b3654c299169c0f815629af51518c775817d09dd04da9a3bfa510cfa63f12bc",
            "9d19ee268e0d7bcf6716e6658ee1b0384a71d6f2f9aa1ae2085610cf7c7b316f",
            "92f85d89e7da0f3af0f527e7300cced784d0ba64249b8c376690599d313cb056",
            "3573c1225d462a8047deb540a10daffee48c949958f8b8842eaebe050daf48bb",
            "45953d25a53f34169be70bf8fc143e66c68cc46d47629b1b76b28be22647c9a9",
            "ac9c5946be2a99aca3f2643a8a98f230414662e4ea47e482ccad6bf6b0517657",
            "ac34a2e0269ced3acc355be706239ee0f3f1e73a035c40dd2fac74827164ee53",
            "f2b566cb887b5c06e04f5cd97660a99e73bd52ceb9d72c6db6383ae8470cc4cf",
            testJarLayerDigest,
            testConfig1Digest,
            testConfig2Digest,
        )
        val hivemqIndexDigest = "d498448faeaf83b9fa66defd14b2cadc168e211bcb78fb36c748c19b5580b699"
        val hivemqManifestDigest = "c18c4e0236f2e8bec242432a19cff1d93bbd422b305e6900809fa4fcf0e07e48"
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
        val redisIndexDigest = "7e41546a32631e803b8bb29865342e1633a71f9ef6f7ab2868baf5361bdf2087"
        val redisManifestDigests = setOf(
            "2279d970250b64a7477b0f77724d6eeba3af43f9b0615309967cb08130d20db0",
            "40da8f877ff1f214018cadff49742c5f9b0481c1638b16cb8d3f52a789f25c7c",
            "5daa2e5cc6effd6011964d2c6761eccd67523815506aa9a57a7c785cb8cb21b9",
            "7e41546a32631e803b8bb29865342e1633a71f9ef6f7ab2868baf5361bdf2087",
            "8d05846ddc09dd0ed18733366630d6a0cdc81b00b302853ec5e66028d418a076",
            "9022dd8ceb38564c256adab18307aba9c4015a7ff44fdefe588f64a04e4fe92e",
            "9cb348db620099108a4fa39f45939c14ea01fd9e407515698549483cf616eb5d",
            "9de7f79ffcea5408bf968ea5b91f6116eafe1a24c97bf5bde348133103955028",
            "a2b880fee292120fe74f7f2e25cdc04e00e80d12ee8f98c53d562d252f8cbb11",
        )
        val redisConfigAndLayerDigests = setOf(
            "075129f30b31cbb1654653588b25f41fd5d899285e15d6fee8739cc39c609ede",
            "184b14480d317057da092a0994ad6baf4b2df588108f43969f8fd56f021af2c6",
            "18a3bc1f6e40633000e515c117c76b86a4748ee69900ec68edcd21e4bc675a10",
            "1b7181eaf68223ddcee59316abf0f05d2e2377dcd7187aae89549641c24390e2",
            "1e98aaf59b67d32794055b335cfdcdb0c4fce301ebb58612a7ff09c11dde1dce",
            "2af58ecfedf0a2327f6d3e09044dff7f47b322963e8a6d6fa2d504ca87179ed0",
            "2def0c28cea214ac0f47ba9db57dbe5ee2740220e96275503c403de29fd283e4",
            "2e7279224f08c0d30578f4dbef204f0d766afabbd04daf59490323251b558dae",
            "2ec361d6f6cf728cf599dd5cbb84a3b43822d164c184534d4650f91ac9cea36f",
            "2f262b87977f78b0ee89f9c4ded66b6dc502aae2136cd6f8d5c720994a377ef6",
            "32617ca14c22b7a6e74fd0287233c89cd82724992d76be7572bfd6d20b50c651",
            "32dedcb42eb5e90b63628945860ba8b939e2057181c8f89cdb2fc2d36fc9f55a",
            "4dfbe3dd27371f1c37af36d90cf012f849a0806aeabd01177d0dd5e1f05b2685",
            "4f4fb700ef54461cfa02571ae0db9a0dc1e0cdb5577484a6d75e68dc38e8acc1",
            "568cf143d108dcce5c6bb165b01c719b75ae6dcb0017e84772613704aa8ebf75",
            "5e306bc7f3cd8273ca2f216836302ad21df95fa6a35edc08f6d45e13364a9fd5",
            "6017239f40fa06f59a01941964103b2a9f1cfa98c18f10c31292fb974d520453",
            "61bc03c307c042b0019919be4aca7c5db8571469a1970bbca53c2125d9eaf35f",
            "64136cebabc3fd206a2e54d36143516c9e9dd18c1a4a6d11d2f12850920bd35e",
            "69aa61ccf55e5bf8e7a069b89e8afb42b4f3443b3785868795af8046d810d608",
            "69db166949ac1fa95d91e84062a1f8d8f91cb193ec8b9e408a252abe079de849",
            "6ca3626dc272a208b340786413012f8e96082886ec9e99777808c335a9a0f5f9",
            "6e771e15690e2fabf2332d3a3b744495411d6e0b00b2aea64419b58b0066cf81",
            "74a524ea8c131a508928d9c19a6f2d460890d24e9fb1476fa3ca15d4830feac2",
            "76099982f06682e28a60c3b774ef20931d07b0a2f551203484e633d8c0361ee7",
            "7a37282d43d3092d42cc7aafd345de189dcb8dc97f7c0c44663ffdbe107dfce4",
            "7df33f7ad8beb367ac09bdd1b2f220db3ee2bbdda14a6310d1340e5628b5ba88",
            "7ec1e82cde0331011d3988f758fecb0e2648189163ac977c79eca78e86015ca3",
            "83f63f5840b3f089bf7d6373046ce1bb5b6e554adcf18738418e7379522becae",
            "85f3b18f9f5a8655db86c6dfb02bb01011ffef63d10a173843c5c65c3e9137b7",
            "878a566114717809f918a100e6a2d5745e2b81ecbb378f3e5908df3026920199",
            "899e5c38d3252b6acc3a973779a2961e0cf93a74e74d647dd5b0a4581705b29f",
            "9826632aa5dd8df6206034a82fe85493f1b7c96691d94fe4abec5ab08a187345",
            "9b92922c442a61cfe54344354ebd3fbc995fa57fb8b78a500f20de74adb2c53f",
            "a0b4e1a52acd02a416da10b437f5d3628e64f33036a331fa34870f5a6d02143a",
            "ac36f2a7f82666649e1b57d6c77644c4f9c1b715872358446fd6f53a3efc7b2a",
            "b38de7eb7132bf24de27447f0d6330d4299fe0264ac584c5f44fa45bfb8da0f3",
            "b490790304f175a292805f9ecc5d125e166eb2123162b16799c043b40349fef2",
            "b782747b1d523d78e2eccdc898011eb18b9e831309cefc10bb6601fcfe3aa1d0",
            "b79bc66df2694d9c2acf38a54dff61e7752ea57aa3cf5c6f105e09b33dec5c1c",
            "bb7e93547ce2a5f3a71b44b5ee5dcc65a14ecd52046ad0ba4a1b239955f014ff",
            "bf3ce4aeaa63f6aadfde06cb0484bcf74cee44042970225bcd113ab3b67f7c3c",
            "c1a599607158512214777614f916f8193d29fd34b656d47dfc26314af01e2af4",
            "c44a25f41bb21e5989391abaeedee4cc0e0680f2370fc86e0cf01c8939d4b662",
            "c7a324ded8895b39df9977bfe580708e3ff9c28d3a7b4fb788f16b001563006f",
            "c7b787002cc542b23400b90a538040c1e62f6c5c4c5f1158b2a39f02bce4f74e",
            "c8a003ad8d70e3afeb6558df4644ea31203ab133164940c38829c4397af22a3b",
            "cb1b308b7bf97cf1c9193f2017908eb1e5f91e062736541675f1c3b99242ed1c",
            "d1d0b116f6bfbf2865b2158e8e42d73252d576795eab15747a6d94ac95a15868",
            "d35a244728108ce0560ebcb807b30a26f93b9f6581d987e525f43c139f9d534b",
            "d87f59363737dd9cf44d245645df515e38a32c2635e96ebf454f189885b4652f",
            "dc68676f3e0357f3cea9d33f03ccb30049845def061097badd6c82664c598c26",
            "ddea5b1619a1f8fd36f2c4a9dc8742e5fc66b38fc3cc43c657bc3b7abc4adc86",
            "e1cad2c79c250951a6e34664a13fbeaa39e870b4c2b68527440877befffb4e56",
            "eae6e1e750cad68b67a3bfcbc96db27a0a94df513b7d71bdbad2a4a6cacd8b87",
            "f18232174bc91741fdf3da96d85011092101a032a93a388b79e99e69c2d5c870",
            "fc10234629e49fd6134d9fcab3db5c1ef67f666879ebeb399d25ef4639f433dc",
        )
        val blobDigests = setOf(
            testIndexDigest,
            testManifest1Digest,
            testManifest2Digest,
            *testConfigAndLayerDigests.toTypedArray(),
            hivemqIndexDigest,
            hivemqManifestDigest,
            *hivemqConfigAndLayerDigests.toTypedArray(),
            redisIndexDigest,
            *redisManifestDigests.toTypedArray(),
            *redisConfigAndLayerDigests.toTypedArray(),
        )

        val registryDir = buildDir.resolve("oci/registries/testSuite")
        assertEquals(
            sortedSetOf(
                *blobDigests.map { "blobs/sha256/${it.substring(0, 2)}/$it/data" }.toTypedArray(),
                *testConfigAndLayerDigests.map { "repositories/example/test/_layers/sha256/$it/link" }.toTypedArray(),
                "repositories/example/test/_manifests/revisions/sha256/$testIndexDigest/link",
                "repositories/example/test/_manifests/revisions/sha256/$testManifest1Digest/link",
                "repositories/example/test/_manifests/revisions/sha256/$testManifest2Digest/link",
                "repositories/example/test/_manifests/tags/1.0.0/current/link",
                "repositories/example/test/_manifests/tags/1.0.0/index/sha256/$testIndexDigest/link",
                "repositories/example/test/_manifests/tags/latest/current/link",
                "repositories/example/test/_manifests/tags/latest/index/sha256/$testIndexDigest/link",
                *hivemqConfigAndLayerDigests.map { "repositories/hivemq/hivemq4/_layers/sha256/$it/link" }
                    .toTypedArray(),
                "repositories/hivemq/hivemq4/_manifests/revisions/sha256/$hivemqIndexDigest/link",
                "repositories/hivemq/hivemq4/_manifests/revisions/sha256/$hivemqManifestDigest/link",
                "repositories/hivemq/hivemq4/_manifests/tags/4.16.0/current/link",
                "repositories/hivemq/hivemq4/_manifests/tags/4.16.0/index/sha256/$hivemqIndexDigest/link",
                *redisConfigAndLayerDigests.map { "repositories/library/redis/_layers/sha256/$it/link" }
                    .toTypedArray(),
                "repositories/library/redis/_manifests/revisions/sha256/$redisIndexDigest/link",
                *redisManifestDigests.map { "repositories/library/redis/_manifests/revisions/sha256/$it/link" }
                    .toTypedArray(),
                "repositories/library/redis/_manifests/tags/8.0.0-alpine/current/link",
                "repositories/library/redis/_manifests/tags/8.0.0-alpine/index/sha256/$redisIndexDigest/link",
            ),
            registryDir.leaves.mapTo(TreeSet()) { it.toRelativeString(registryDir) },
        )
    }
}

private val File.leaves: Set<File>
    get() {
        val leaves = HashSet<File>()
        for (file in walkTopDown()) {
            leaves += file
            leaves -= file.parentFile
        }
        return leaves
    }
