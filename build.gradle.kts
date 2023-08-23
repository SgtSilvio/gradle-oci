plugins {
    `kotlin-dsl`
    alias(libs.plugins.pluginPublish)
    alias(libs.plugins.defaults)
    alias(libs.plugins.metadata)
}

group = "io.github.sgtsilvio.gradle"
description = "Gradle plugin to ease producing (multi-arch) OCI images without requiring external tools"

metadata {
    readableName.set("Gradle OCI Plugin")
    license {
        apache2()
    }
    developers {
        register("SgtSilvio") {
            fullName.set("Silvio Giebl")
        }
    }
    github {
        org.set("SgtSilvio")
        issues()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
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
        exclude("io.netty", "netty-resolver-dns-native-macos")
        exclude("io.netty", "netty-transport-native-epoll")
        exclude("io.netty.incubator", "netty-incubator-codec-native-quic")
    }
    implementation(platform(libs.netty.bom))
}

gradlePlugin {
    website.set(metadata.url)
    vcsUrl.set(metadata.scm.get().url)
    plugins {
        create("oci") {
            id = "$group.$name"
            implementationClass = "$group.$name.OciPlugin"
            displayName = metadata.readableName.get()
            description = project.description
            tags.set(listOf("oci", "oci-image", "docker", "multi-arch"))
        }
    }
}

testing {
    suites.named<JvmTestSuite>("test") {
        useJUnitJupiter(libs.versions.junit.jupiter)
    }
}
