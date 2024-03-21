package org.example.java.app;

import io.github.sgtsilvio.gradle.oci.junit.jupiter.OciImages;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

class ImageTest {

    @Test
    void start() {
        try (final GenericContainer<?> container = new GenericContainer(OciImages.getImageName("example/example-java-app"))) {
            container.waitingFor(Wait.forLogMessage("Hello World!\n", 1));
            container.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8StringWithoutLineEnding()));
            container.start();
        }
    }
}
