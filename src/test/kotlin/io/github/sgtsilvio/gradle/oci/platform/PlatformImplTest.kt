package io.github.sgtsilvio.gradle.oci.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author Silvio Giebl
 */
class PlatformTest {

    @Test
    fun `toString nothingEmpty`() {
        val platform = Platform("os", "arch", "variant", "123", sortedSetOf("f1", "f2"))
        assertEquals("@os,arch,variant,123,f1,f2", platform.toString())
    }

    @Test
    fun `toString emptyVariant`() {
        val platform = Platform("os", "arch", "", "123", sortedSetOf("f1", "f2"))
        assertEquals("@os,arch,,123,f1,f2", platform.toString())
    }

    @Test
    fun `toString emptyVariant emptyOsFeatures`() {
        val platform = Platform("os", "arch", "", "123", sortedSetOf())
        assertEquals("@os,arch,,123", platform.toString())
    }

    @Test
    fun `toString emptyVariant emptyOsVersion`() {
        val platform = Platform("os", "arch", "", "", sortedSetOf("f1", "f2"))
        assertEquals("@os,arch,,,f1,f2", platform.toString())
    }

    @Test
    fun `toString emptyVariant emptyOsVersion emptyOsFeatures`() {
        val platform = Platform("os", "arch", "", "", sortedSetOf())
        assertEquals("@os,arch", platform.toString())
    }

    @Test
    fun `toString emptyOsVersion`() {
        val platform = Platform("os", "arch", "variant", "", sortedSetOf("f1", "f2"))
        assertEquals("@os,arch,variant,,f1,f2", platform.toString())
    }

    @Test
    fun `toString emptyOsVersion emptyOsFeatures`() {
        val platform = Platform("os", "arch", "variant", "", sortedSetOf())
        assertEquals("@os,arch,variant", platform.toString())
    }

    @Test
    fun `toString emptyOsFeatures`() {
        val platform = Platform("os", "arch", "variant", "123", sortedSetOf())
        assertEquals("@os,arch,variant,123", platform.toString())
    }
}