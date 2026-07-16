package io.github.sgtsilvio.gradle.oci.internal.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * @author Yonathan Mengesha
 */
class OciRegistryResourceScopeTest {

    @Test
    fun `decodeToResourceScopeOrNull singleAction`() {
        assertEquals(
            OciRegistryResourceScope("repository", "library/eclipse-temurin", setOf("pull")),
            "repository:library/eclipse-temurin:pull".decodeToResourceScopeOrNull(),
        )
    }

    @Test
    fun `decodeToResourceScopeOrNull multipleActions`() {
        assertEquals(
            OciRegistryResourceScope("repository", "library/eclipse-temurin", setOf("pull", "push")),
            "repository:library/eclipse-temurin:pull,push".decodeToResourceScopeOrNull(),
        )
    }

    @Test
    fun `decodeToResourceScopeOrNull tooFewParts`() {
        // the AWS ECR Public registry responds with this scope, regardless of the requested resource
        assertNull("aws".decodeToResourceScopeOrNull())
        assertNull("repository:library/eclipse-temurin".decodeToResourceScopeOrNull())
    }

    @Test
    fun `decodeToResourceScopeOrNull tooManyParts`() {
        assertNull("repository:library/eclipse-temurin:pull:push".decodeToResourceScopeOrNull())
    }

    @Test
    fun `encodeToString decodeToResourceScopeOrNull roundtrip`() {
        val scope = "repository:library/eclipse-temurin:pull"
        assertEquals(scope, scope.decodeToResourceScopeOrNull()?.encodeToString())
    }
}
