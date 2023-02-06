package io.github.sgtsilvio.gradle.oci.internal.glob

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author Silvio Giebl
 */
internal class GlobUtilTest {

    @Test
    fun `convertGlobToRegex single star`() {
        assertEquals("[^/]*", convertGlobToRegex("*"))
    }

    @Test
    fun `convertGlobToRegex double star`() {
        assertEquals(".*", convertGlobToRegex("**"))
    }

    @Test
    fun `convertGlobToRegex slash single star`() {
        assertEquals("/[^/]*", convertGlobToRegex("/*"))
    }

    @Test
    fun `convertGlobToRegex slash double star`() {
        assertEquals("/.*", convertGlobToRegex("/**"))
    }

    @Test
    fun `convertGlobToRegex single star slash`() {
        assertEquals("[^/]*/", convertGlobToRegex("*/"))
    }

    @Test
    fun `convertGlobToRegex double star slash`() {
        assertEquals("(?:.*/)?", convertGlobToRegex("**/"))
    }

    @Test
    fun `convertGlobToRegex slash single star slash`() {
        assertEquals("/[^/]*/", convertGlobToRegex("/*/"))
    }

    @Test
    fun `convertGlobToRegex slash double star slash`() {
        assertEquals("/(?:.*/)?", convertGlobToRegex("/**/"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star`() {
        assertEquals("foo/[^/]*", convertGlobToRegex("foo/*"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star`() {
        assertEquals("foo/.*", convertGlobToRegex("foo/**"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star slash`() {
        assertEquals("foo/[^/]*/", convertGlobToRegex("foo/*/"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star slash`() {
        assertEquals("foo/(?:.*/)?", convertGlobToRegex("foo/**/"))
    }

    @Test
    fun `convertGlobToRegex single star slash foo`() {
        assertEquals("[^/]*/foo", convertGlobToRegex("*/foo"))
    }

    @Test
    fun `convertGlobToRegex double star slash foo`() {
        assertEquals("(?:.*/)?foo", convertGlobToRegex("**/foo"))
    }

    @Test
    fun `convertGlobToRegex slash single star slash foo`() {
        assertEquals("/[^/]*/foo", convertGlobToRegex("/*/foo"))
    }

    @Test
    fun `convertGlobToRegex slash double star slash foo`() {
        assertEquals("/(?:.*/)?foo", convertGlobToRegex("/**/foo"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star slash bar`() {
        assertEquals("foo/[^/]*/bar", convertGlobToRegex("foo/*/bar"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star slash bar`() {
        assertEquals("foo/(?:.*/)?bar", convertGlobToRegex("foo/**/bar"))
    }

    @Test
    fun `convertGlobToRegex foo single star`() {
        assertEquals("foo[^/]*", convertGlobToRegex("foo*"))
    }

    @Test
    fun `convertGlobToRegex foo double star`() {
        assertEquals("foo.*", convertGlobToRegex("foo**"))
    }

    @Test
    fun `convertGlobToRegex foo single star slash`() {
        assertEquals("foo[^/]*/", convertGlobToRegex("foo*/"))
    }

    @Test
    fun `convertGlobToRegex foo double star slash`() {
        assertEquals("foo.*/", convertGlobToRegex("foo**/"))
    }

    @Test
    fun `convertGlobToRegex foo single star slash bar`() {
        assertEquals("foo[^/]*/bar", convertGlobToRegex("foo*/bar"))
    }

    @Test
    fun `convertGlobToRegex foo double star slash bar`() {
        assertEquals("foo.*/bar", convertGlobToRegex("foo**/bar"))
    }

    @Test
    fun `convertGlobToRegex single star foo`() {
        assertEquals("[^/]*foo", convertGlobToRegex("*foo"))
    }

    @Test
    fun `convertGlobToRegex double star foo`() {
        assertEquals(".*foo", convertGlobToRegex("**foo"))
    }

    @Test
    fun `convertGlobToRegex slash single star foo`() {
        assertEquals("/[^/]*foo", convertGlobToRegex("/*foo"))
    }

    @Test
    fun `convertGlobToRegex slash double star foo`() {
        assertEquals("/.*foo", convertGlobToRegex("/**foo"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star bar`() {
        assertEquals("foo/[^/]*bar", convertGlobToRegex("foo/*bar"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star bar`() {
        assertEquals("foo/.*bar", convertGlobToRegex("foo/**bar"))
    }

    @Test
    fun `convertGlobToRegex foo single star bar`() {
        assertEquals("foo[^/]*bar", convertGlobToRegex("foo*bar"))
    }

    @Test
    fun `convertGlobToRegex foo double star bar`() {
        assertEquals("foo.*bar", convertGlobToRegex("foo**bar"))
    }

    @Test
    fun `convertGlobToRegex double star slash single star`() {
        assertEquals("(?:.*/)?[^/]*", convertGlobToRegex("**/*"))
    }

    @Test
    fun `convertGlobToRegex single star slash double star`() {
        assertEquals("[^/]*/.*", convertGlobToRegex("*/**"))
    }

    @Test
    fun `convertGlobToRegex slash double star slash single star`() {
        assertEquals("/(?:.*/)?[^/]*", convertGlobToRegex("/**/*"))
    }

    @Test
    fun `convertGlobToRegex slash single star slash double star`() {
        assertEquals("/[^/]*/.*", convertGlobToRegex("/*/**"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star slash single star`() {
        assertEquals("foo/(?:.*/)?[^/]*", convertGlobToRegex("foo/**/*"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star slash double star`() {
        assertEquals("foo/[^/]*/.*", convertGlobToRegex("foo/*/**"))
    }

    @Test
    fun `convertGlobToRegex double star slash single star slash`() {
        assertEquals("(?:.*/)?[^/]*/", convertGlobToRegex("**/*/"))
    }

    @Test
    fun `convertGlobToRegex single star slash double star slash`() {
        assertEquals("[^/]*/(?:.*/)?", convertGlobToRegex("*/**/"))
    }

    @Test
    fun `convertGlobToRegex double star slash single star slash foo`() {
        assertEquals("(?:.*/)?[^/]*/foo", convertGlobToRegex("**/*/foo"))
    }

    @Test
    fun `convertGlobToRegex single star slash double star slash foo`() {
        assertEquals("[^/]*/(?:.*/)?foo", convertGlobToRegex("*/**/foo"))
    }

    @Test
    fun `convertGlobToRegex slash double star slash single star slash`() {
        assertEquals("/(?:.*/)?[^/]*/", convertGlobToRegex("/**/*/"))
    }

    @Test
    fun `convertGlobToRegex slash single star slash double star slash`() {
        assertEquals("/[^/]*/(?:.*/)?", convertGlobToRegex("/*/**/"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star slash single star slash`() {
        assertEquals("foo/(?:.*/)?[^/]*/", convertGlobToRegex("foo/**/*/"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star slash double star slash`() {
        assertEquals("foo/[^/]*/(?:.*/)?", convertGlobToRegex("foo/*/**/"))
    }

    @Test
    fun `convertGlobToRegex slash double star slash single star slash foo`() {
        assertEquals("/(?:.*/)?[^/]*/foo", convertGlobToRegex("/**/*/foo"))
    }

    @Test
    fun `convertGlobToRegex slash single star slash double star slash foo`() {
        assertEquals("/[^/]*/(?:.*/)?foo", convertGlobToRegex("/*/**/foo"))
    }

    @Test
    fun `convertGlobToRegex foo slash double star slash single star slash bar`() {
        assertEquals("foo/(?:.*/)?[^/]*/bar", convertGlobToRegex("foo/**/*/bar"))
    }

    @Test
    fun `convertGlobToRegex foo slash single star slash double star slash bar`() {
        assertEquals("foo/[^/]*/(?:.*/)?bar", convertGlobToRegex("foo/*/**/bar"))
    }
}