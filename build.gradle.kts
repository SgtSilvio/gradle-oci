plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.defaults)
    alias(libs.plugins.metadata)
}

group = "io.github.sgtsilvio.gradle"
description = "Gradle plugin to ease producing (multi-arch) OCI images without requiring external tools"

metadata {
    readableName = "Gradle OCI Plugin"
    license {
        apache2()
    }
    developers {
        register("SgtSilvio") {
            fullName = "Silvio Giebl"
        }
    }
    github {
        org = "SgtSilvio"
        issues()
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.caffeine)
    implementation(libs.commons.codec)
    implementation(libs.commons.compress)
    implementation(libs.commons.io)
    implementation(libs.json)
    implementation(libs.reactor.kotlin)
    implementation(libs.reactor.netty) {
        exclude("io.netty", "netty-transport-native-epoll")
        exclude("io.netty.incubator", "netty-incubator-codec-native-quic")
    }
    implementation(platform(libs.netty.bom))
    runtimeOnly(variantOf(libs.netty.resolver.dns.native.macos) { classifier("osx-aarch_64") })
    runtimeOnly(variantOf(libs.netty.resolver.dns.native.macos) { classifier("osx-x86_64") })
}

gradlePlugin {
    website = metadata.url
    vcsUrl = metadata.scm.get().url
    plugins {
        create("oci") {
            id = "$group.$name"
            implementationClass = "$group.$name.OciPlugin"
            displayName = metadata.readableName.get()
            description = project.description
            tags = listOf("oci", "oci-image", "docker", "multi-arch")
        }
    }
}

signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
}

testing {
    suites {
        withType<JvmTestSuite> {
            useJUnitJupiter(libs.versions.junit.jupiter)
        }
        register<JvmTestSuite>("functionalTest") {
            dependencies {
                implementation(libs.json)
            }
            targets.configureEach {
                testTask {
                    environment(
                        "ORG_GRADLE_PROJECT_dockerHubUsername" to project.property("dockerHubUsername"),
                        "ORG_GRADLE_PROJECT_dockerHubPassword" to project.property("dockerHubPassword"),
                    )
                }
            }
        }
    }
}

gradlePlugin {
    testSourceSets(sourceSets["functionalTest"])
}
