@Suppress("DSL_SCOPE_VIOLATION")
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
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.json)
    implementation("com.google.cloud.tools:jib-core:0.21.0")
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
        useJUnitJupiter(libs.versions.junit.jupiter.get())
    }
}
