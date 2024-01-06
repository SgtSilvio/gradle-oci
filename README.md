# Gradle OCI Plugin

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.sgtsilvio.gradle.oci?color=brightgreen&style=for-the-badge)](https://plugins.gradle.org/plugin/io.github.sgtsilvio.gradle.oci)
[![GitHub](https://img.shields.io/github/license/sgtsilvio/gradle-oci?color=brightgreen&style=for-the-badge)](LICENSE)
[![GitHub Workflow Status (with branch)](https://img.shields.io/github/actions/workflow/status/sgtsilvio/gradle-oci/check.yml?branch=main&style=for-the-badge)](https://github.com/SgtSilvio/gradle-oci/actions/workflows/check.yml?query=branch%3Amain)

Gradle plugin to ease using and producing (multi-arch) OCI images without requiring external tools.

## Why OCI and Not Docker?

OCI is the acronym of the [Open Container Initiative](https://github.com/opencontainers) which creates open standards around container technology, previously mainly driven by Docker.
People still commonly use the term _Docker image_ even if talking about _OCI images_.
So if you are looking for building Docker images, you are right here.
Whenever OCI is mentioned on this page, just think of it as the successor of Docker (simplified).

This plugin allows:
- Building OCI images according to the [OCI Image Format Specification](https://github.com/opencontainers/image-spec)
- Pulling and pushing images from/to OCI registries according to the [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec)

## Why Another Image Builder?

This plugin does not aim to only be "yet another image builder", but instead tries to solve usability issues of other images builders.

### OCI Images as Plain Files

OCI images are a form of distribution of your application.
You may know other distribution formats (deb, rpm, some zip/tar); all of them are just plain files with some metadata.
The same applies to OCI images which consist of filesystem layers (tar files) and metadata (json files).

The problem here is that most image builders hide these files from the user.
This creates multiple usability issues.
First, it makes it harder to grasp what an image actually is.
While the previous point might be subjective, the software and services needed for managing the files definitely complicate the development environment.
Examples for the software in question here are the Docker daemon and registries.

This plugin simply outputs all artifacts of an OCI image as files; no Docker daemon or registry required.
Having access to the plain files of an OCI image is beneficial. Copying files and consuming files in other tools is easy.

### WIP

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
    id("io.github.sgtsilvio.gradle.oci") version "0.6.0"
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
