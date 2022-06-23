@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    `kotlin-dsl`
    `maven-publish`
    alias(libs.plugins.plugin.publish)
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
        repo.set("gradle-oci")
        issues()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("oci") {
            id = "$group.$name"
            displayName = metadata.readableName.get()
            description = project.description
            implementationClass = "$group.$name.OCIPlugin"
        }
    }
}

pluginBundle {
    website = metadata.url.get()
    vcsUrl = metadata.scm.get().url.get()
    tags = listOf("oci", "oci-image", "docker", "multi-arch")
}


testing {
    suites.named<JvmTestSuite>("test") {
        useJUnitJupiter(libs.versions.junit.jupiter.get())
    }
}
