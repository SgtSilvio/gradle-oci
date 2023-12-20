# Gradle OCI Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.sgtsilvio.gradle.oci?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/io.github.sgtsilvio.gradle.oci)
[![GitHub](https://img.shields.io/github/license/sgtsilvio/gradle-oci?color=brightgreen&style=for-the-badge)](LICENSE)

[//]: # ([![GitHub Workflow Status &#40;with branch&#41;]&#40;https://img.shields.io/github/actions/workflow/status/sgtsilvio/gradle-oci/check.yml?branch=master&style=for-the-badge&#41;]&#40;https://github.com/SgtSilvio/gradle-oci/actions/workflows/check.yml?query=branch%3Amaster&#41;)

Gradle plugin to ease producing (multi-arch) OCI images without requiring external tools.

## How to Use

The following is an example of a basic hello world Java application that is bundled as an OCI image and then executed as a Testcontainer in a JUnit test.

`settings.gradle.kts`

```kotlin
rootProject.name = "oci-demo"
```

`build.gradle.kts`

```kotlin
plugins {
    java
    id("io.github.sgtsilvio.gradle.oci") version "0.5.0"
}

group = "org.example"
version = "1.0.0"

tasks.jar {
    manifest.attributes("Main-Class" to "org.example.oci.demo.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.testcontainers:testcontainers:1.19.0")
    testImplementation("io.github.sgtsilvio:gradle-oci-junit-jupiter:0.1.0")
}

tasks.test {
    useJUnitPlatform()
}

oci {
    registries {
        dockerHub {
            credentials()
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
    imageDependencies.forTest(tasks.test) {
        add(project)
    }
}
```

`src/main/java/org/example/oci/demo/Main.java`

```java
package org.example.oci.demo;

public class Main {
    public static void main(final String[] args) {
        System.out.println("Hello world!");
        System.out.println(System.getProperty("java.version"));
    }
}
```

`src/test/java/org/example/oci/demo/ImageTest.java`

```java
package org.example.oci.demo;

import io.github.sgtsilvio.gradle.oci.junit.jupiter.OciImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

public class ImageTest {

    @Test
    void test() throws InterruptedException {
        final GenericContainer<?> container = new GenericContainer(OciImages.getImageName("example/oci-demo:1.0.0"));
        container.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8StringWithoutLineEnding()));
        container.start();
        Thread.sleep(100);
        container.stop();
    }
}
```

## Requirements

- Gradle 7.4 or higher
