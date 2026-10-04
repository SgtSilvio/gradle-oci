package io.github.sgtsilvio.gradle.oci.internal.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * @author David Sondermann
 */
internal class OciRegistryApiTest {

    @Test
    fun `createTokenUri encodes service and scopes`() {
        assertEquals(
            URI("https://container-registry.oracle.com/auth?service=Oracle+Registry&scope=repository%3Ajava%2Fjdk%3Apull"),
            createTokenUri(
                "https://container-registry.oracle.com/auth",
                "Oracle Registry",
                listOf("repository:java/jdk:pull"),
            ),
        )
    }

    @Test
    fun `createTokenUri multiple scopes`() {
        assertEquals(
            URI("https://auth.docker.io/token?service=registry.docker.io&scope=repository%3Alibrary%2Fbusybox%3Apull&scope=repository%3Alibrary%2Falpine%3Apull%2Cpush"),
            createTokenUri(
                "https://auth.docker.io/token",
                "registry.docker.io",
                listOf("repository:library/busybox:pull", "repository:library/alpine:pull,push"),
            ),
        )
    }
}
