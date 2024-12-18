plugins {
    `kotlin-dsl`
    signing
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.defaults)
    alias(libs.plugins.metadata)
}

group = "io.github.sgtsilvio.gradle"

metadata {
    readableName = "Gradle OCI Plugin"
    description = "Gradle plugin to ease using and producing (multi-arch) OCI images without requiring external tools"
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
    implementation(libs.commons.lang)
    implementation(libs.json)
    implementation(libs.reactor.kotlin)
    implementation(libs.reactor.netty) {
        exclude("io.netty", "netty-transport-native-epoll")
        exclude("io.netty.incubator", "netty-incubator-codec-native-quic")
    }
    implementation(platform(libs.netty.bom))
    runtimeOnly(variantOf(libs.netty.resolver.dns.native.macos) { classifier("osx-aarch_64") })
    runtimeOnly(variantOf(libs.netty.resolver.dns.native.macos) { classifier("osx-x86_64") })
    implementation(libs.oci.registry)
}

gradlePlugin {
    plugins {
        create("oci") {
            id = "$group.$name"
            implementationClass = "$group.$name.OciPlugin"
            tags = listOf("oci", "oci-image", "docker", "docker-image", "multi-arch")
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
                        "ORG_GRADLE_PROJECT_dockerHubUsername" to ToStringProvider(providers.gradleProperty("dockerHubUsername")),
                        "ORG_GRADLE_PROJECT_dockerHubPassword" to ToStringProvider(providers.gradleProperty("dockerHubPassword")),
                    )
                }
            }
        }
    }

    tasks.check {
        dependsOn(suites)
    }

    gradlePlugin {
        testSourceSets(sourceSets["functionalTest"])
    }
}

class ToStringProvider(private val provider: Provider<String>) {
    override fun toString() = provider.get()
}
