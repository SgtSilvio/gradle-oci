import java.time.Instant

plugins {
    java
    alias(libs.plugins.oci)
}

group = "org.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

tasks.jar {
    manifest.attributes["Main-Class"] = "org.example.java.app.Main"
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
            parentImages {
                add("library:eclipse-temurin:21.0.2_13-jre-jammy")
            }
            config {
                creationTime = Instant.EPOCH
                entryPoint = listOf("java", "-jar")
                entryPoint.add(tasks.jar.flatMap { it.archiveFileName })
            }
            layers {
                layer("app") {
                    contents {
                        from(tasks.jar)
                    }
                }
            }
        }
    }
}

testing {
    suites {
        "test"(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(libs.testcontainers)
                implementation(libs.gradleOci.junitJupiter)
            }
            ociImageDependencies {
                runtime(project).tag("latest")
            }
        }
    }
}
