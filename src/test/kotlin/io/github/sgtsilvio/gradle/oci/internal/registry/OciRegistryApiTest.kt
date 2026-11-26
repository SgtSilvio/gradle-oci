package io.github.sgtsilvio.gradle.oci.internal.registry

import io.netty.handler.codec.http.DefaultHttpHeaders
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

    @Test
    fun `HttpResponseException message contains request and response`() {
        val headers = DefaultHttpHeaders().add("Content-Length", "70")
        val exception = HttpResponseException(
            "HEAD",
            "http://localhost:5000/v2/hivemq/blobs/sha256:0123",
            null,
            500,
            headers,
            "",
        )
        assertEquals(
            "unexpected http response: 500\nHEAD http://localhost:5000/v2/hivemq/blobs/sha256:0123\nContent-Length: 70\n",
            exception.message,
        )
    }

    @Test
    fun `HttpResponseException message contains redirect url`() {
        val headers = DefaultHttpHeaders().add("Content-Type", "application/xml")
        val exception = HttpResponseException(
            "GET",
            "https://registry.example.com/v2/hivemq/blobs/sha256:0123",
            "https://cdn.example.com/blobs/sha256/01/0123/data",
            403,
            headers,
            "<Error><Code>AccessDenied</Code></Error>",
        )
        assertEquals(
            "unexpected http response: 403\nGET https://registry.example.com/v2/hivemq/blobs/sha256:0123\n(redirected to https://cdn.example.com/blobs/sha256/01/0123/data)\nContent-Type: application/xml\n<Error><Code>AccessDenied</Code></Error>",
            exception.message,
        )
    }

    @Test
    fun `toSchemeHostAndPath removes user info, query and fragment`() {
        assertEquals(
            "https://cdn.example.com:8443/blobs/sha256/01/0123/data",
            "https://user:password@cdn.example.com:8443/blobs/sha256/01/0123/data?X-Amz-Signature=secret#fragment".toSchemeHostAndPath(),
        )
    }

    @Test
    fun `toSchemeHostAndPath without port`() {
        assertEquals(
            "https://cdn.example.com/blobs/sha256/01/0123/data",
            "https://cdn.example.com/blobs/sha256/01/0123/data?verify=secret".toSchemeHostAndPath(),
        )
    }
}
