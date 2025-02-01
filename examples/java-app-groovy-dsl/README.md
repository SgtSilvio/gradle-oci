# Gradle OCI Plugin Example: Java Application

> [!NOTE]
> This example is the same as [java-app](../java-app).
> The only difference is the use of the Groovy DSL in Gradle build scripts.
> The Kotlin DSL is recommended if you use the Gradle OCI Plugin as the focus lies on providing the best user experience with the Kotlin DSL.
> It is possible to use the Groovy DSL though, there are only a few differences where the Groovy DSL needs a few more characters.

This example project produces a Java application that prints "Hello World!" and the Java version.

The application is bundled as an OCI/Docker image by using the Gradle OCI Plugin.
Look at the OCI image definition in [build.gradle.kts](build.gradle).

This example lets you use the created OCI image in the following ways:
- Push the image to a registry:
  - To push to Docker Hub, set the `dockerHubUsername` and `dockerHubPassword` Gradle properties to your Docker credentials (for example in `~/.gradle.properties`) and replace `<username>` with your Docker username in the following command:
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
  - You can specify the image's name and tags in the registry with the `--name` and `--tag` options:
    ```shell
    ./gradlew pushOciImage --url https://my-registry.example.org --name oci-demo-app --tag 123 --tag latest
    ```
  - Run the following to get an overview and a description of all available options:
    ```shell
    ./gradlew help --task pushOciImage
    ```
- Store the image as an OCI image layout:
  ```shell
  ./gradlew ociImageLayout
  ```
  - The above command creates the OCI image layout in a directory. Use the `--tar` option to additionally create a tar containing the OCI image layout (compatible with `docker load`):
    ```shell
    ./gradlew ociImageLayout --tar
    ```
  - You can specify the image's name and tags in the OCI image layout with the `--name` and `--tag` options:
    ```shell
    ./gradlew ociImageLayout --name oci-demo-app --tag 123 --tag latest
    ```
  - Run the following to get an overview and a description of all available options:
    ```shell
    ./gradlew help --task ociImageLayout
    ```
- Load the image to the Docker image store:
  ```shell
  ./gradlew loadOciImage
  ```
  - You can specify the image's name and tags in the Docker image store with the `--name` and `--tag` options:
    ```shell
    ./gradlew loadOciImage --name oci-demo-app --tag 123 --tag latest
    ```
  - Run the following to get an overview and a description of all available options:
    ```shell
    ./gradlew help --task loadOciImage
    ```
- Run the image as a test container in a JUnit test (Look at the code [here](src/test/java/org/example/java/app/ImageTest.java)):
  ```shell
  ./gradlew test
  ```
