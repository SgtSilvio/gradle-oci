package io.github.sgtsilvio.gradle.oci.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OciImageMapperTest {

    @Test
    fun group_isMappedToImageNamespace() {
        assertEquals("", defaultMappedImageNamespace(""))
        assertEquals("library/", defaultMappedImageNamespace("library"))
        assertEquals("google/cloudsdktool/", defaultMappedImageNamespace("com.google.cloudsdktool"))
    }

    @Test
    fun registryQualifiedGroup_isMappedToImageNamespaceAsIs() {
        assertEquals("library/", defaultMappedImageNamespace("registry-1.docker.io!library"))
        assertEquals("base-images/", defaultMappedImageNamespace("public.ecr.aws!base-images"))
        assertEquals("hivemq/base-images/", defaultMappedImageNamespace("public.ecr.aws!hivemq.base-images"))
        assertEquals("", defaultMappedImageNamespace("registry-1.docker.io!"))
    }
}
