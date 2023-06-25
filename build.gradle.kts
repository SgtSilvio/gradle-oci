plugins {
    `kotlin-dsl`
    alias(libs.plugins.plugin.publish)
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
    implementation(libs.reactor.netty)
    implementation(platform(libs.netty.bom))
    runtimeOnly(variantOf(libs.netty.resovler.dns.native.macos) {
        classifier("osx-aarch_64")
    })
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
