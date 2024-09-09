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
            dependencies {
                runtime("library:eclipse-temurin:21.0.2_13-jre-jammy")
            }
            config {
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
            oci.of(this) {
                imageDependencies {
                    runtime(project).tag("latest")
                }
            }
        }
    }
}
