# Gradle OCI Plugin Example: Java Application

This example project produces a Java application that prints "Hello World!" and the Java version.

The application is bundled as an OCI/Docker image by using the Gradle OCI Plugin.
Look at the OCI image definition in [build.gradle.kts](build.gradle.kts).

This example gives you two options to use the created OCI image:
- Run the image as a test container in a JUnit test (Look at the code [here](src/test/java/org/example/java/app/ImageTest.java)):
  ```shell
  ./gradlew test
  ```
- Push the image to a registry:
  - To push to DockerHub, set the `dockerHubUsername` and `dockerHubPassword` Gradle properties to your DockerHub credentials (for example in `~/.gradle.properties`) and replace `<username>` in the following command:
    ```shell
    ./gradlew pushOciImage --registry dockerHub --name <username>/example-java-app
    ```
  - To push to any registry, specify the `-url` option:
    ```shell
    ./gradlew pushOciImage --url https://my-registry.example.org
    ```
  - To push to any registry that requires credentials, also specify the `--credentials` option and setup the required Gradle properties (`myRegUsername` and `myRegPassword` in this example):
    ```shell
    ./gradlew pushOciImage --url https://my-registry.example.org --credentials myReg
    ```
  - You can specify the OCI image's name and tag in the registry with the `--name` and `--tag` options:
    ```shell
    ./gradlew pushOciImage --url https://my-registry.example.org --name oci-demo-app --tag 123 --tag latest
    ```
  - Run the following to get an overview and a description of all available options:
    ```shell
    ./gradlew help --task pushOciImage
    ```
