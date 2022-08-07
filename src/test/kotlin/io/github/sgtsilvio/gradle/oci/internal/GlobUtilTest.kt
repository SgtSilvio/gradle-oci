package io.github.sgtsilvio.gradle.oci.internal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * @author Silvio Giebl
 */
class GlobUtilTest {

    @Test
    fun `convertToRegex single star`() {
        assertEquals("[^/]*", convertToRegex("*"))
    }

    @Test
    fun `convertToRegex double star`() {
        assertEquals(".*", convertToRegex("**"))
    }

    @Test
    fun `convertToRegex slash single star`() {
        assertEquals("/[^/]*", convertToRegex("/*"))
    }

    @Test
    fun `convertToRegex slash double star`() {
        assertEquals("/.*", convertToRegex("/**"))
    }

    @Test
    fun `convertToRegex single star slash`() {
        assertEquals("[^/]*/", convertToRegex("*/"))
    }

    @Test
    fun `convertToRegex double star slash`() {
        assertEquals("(?:.*/)?", convertToRegex("**/"))
    }

    @Test
    fun `convertToRegex slash single star slash`() {
        assertEquals("/[^/]*/", convertToRegex("/*/"))
    }

    @Test
    fun `convertToRegex slash double star slash`() {
        assertEquals("/(?:.*/)?", convertToRegex("/**/"))
    }

    @Test
    fun `convertToRegex foo slash single star`() {
        assertEquals("foo/[^/]*", convertToRegex("foo/*"))
    }

    @Test
    fun `convertToRegex foo slash double star`() {
        assertEquals("foo/.*", convertToRegex("foo/**"))
    }

    @Test
    fun `convertToRegex foo slash single star slash`() {
        assertEquals("foo/[^/]*/", convertToRegex("foo/*/"))
    }

    @Test
    fun `convertToRegex foo slash double star slash`() {
        assertEquals("foo/(?:.*/)?", convertToRegex("foo/**/"))
    }

    @Test
    fun `convertToRegex single star slash foo`() {
        assertEquals("[^/]*/foo", convertToRegex("*/foo"))
    }

    @Test
    fun `convertToRegex double star slash foo`() {
        assertEquals("(?:.*/)?foo", convertToRegex("**/foo"))
    }

    @Test
    fun `convertToRegex slash single star slash foo`() {
        assertEquals("/[^/]*/foo", convertToRegex("/*/foo"))
    }

    @Test
    fun `convertToRegex slash double star slash foo`() {
        assertEquals("/(?:.*/)?foo", convertToRegex("/**/foo"))
    }

    @Test
    fun `convertToRegex foo slash single star slash bar`() {
        assertEquals("foo/[^/]*/bar", convertToRegex("foo/*/bar"))
    }

    @Test
    fun `convertToRegex foo slash double star slash bar`() {
        assertEquals("foo/(?:.*/)?bar", convertToRegex("foo/**/bar"))
    }

    @Test
    fun `convertToRegex foo single star`() {
        assertEquals("foo[^/]*", convertToRegex("foo*"))
    }

    @Test
    fun `convertToRegex foo double star`() {
        assertEquals("foo.*", convertToRegex("foo**"))
    }

    @Test
    fun `convertToRegex foo single star slash`() {
        assertEquals("foo[^/]*/", convertToRegex("foo*/"))
    }

    @Test
    fun `convertToRegex foo double star slash`() {
        assertEquals("foo.*/", convertToRegex("foo**/"))
    }

    @Test
    fun `convertToRegex foo single star slash bar`() {
        assertEquals("foo[^/]*/bar", convertToRegex("foo*/bar"))
    }

    @Test
    fun `convertToRegex foo double star slash bar`() {
        assertEquals("foo.*/bar", convertToRegex("foo**/bar"))
    }

    @Test
    fun `convertToRegex single star foo`() {
        assertEquals("[^/]*foo", convertToRegex("*foo"))
    }

    @Test
    fun `convertToRegex double star foo`() {
        assertEquals(".*foo", convertToRegex("**foo"))
    }

    @Test
    fun `convertToRegex slash single star foo`() {
        assertEquals("/[^/]*foo", convertToRegex("/*foo"))
    }

    @Test
    fun `convertToRegex slash double star foo`() {
        assertEquals("/.*foo", convertToRegex("/**foo"))
    }

    @Test
    fun `convertToRegex foo slash single star bar`() {
        assertEquals("foo/[^/]*bar", convertToRegex("foo/*bar"))
    }

    @Test
    fun `convertToRegex foo slash double star bar`() {
        assertEquals("foo/.*bar", convertToRegex("foo/**bar"))
    }

    @Test
    fun `convertToRegex foo single star bar`() {
        assertEquals("foo[^/]*bar", convertToRegex("foo*bar"))
    }

    @Test
    fun `convertToRegex foo double star bar`() {
        assertEquals("foo.*bar", convertToRegex("foo**bar"))
    }

    @Test
    fun `convertToRegex double star slash single star`() {
        assertEquals("(?:.*/)?[^/]*", convertToRegex("**/*"))
    }

    @Test
    fun `convertToRegex single star slash double star`() {
        assertEquals("[^/]*/.*", convertToRegex("*/**"))
    }

    @Test
    fun `convertToRegex slash double star slash single star`() {
        assertEquals("/(?:.*/)?[^/]*", convertToRegex("/**/*"))
    }

    @Test
    fun `convertToRegex slash single star slash double star`() {
        assertEquals("/[^/]*/.*", convertToRegex("/*/**"))
    }

    @Test
    fun `convertToRegex foo slash double star slash single star`() {
        assertEquals("foo/(?:.*/)?[^/]*", convertToRegex("foo/**/*"))
    }

    @Test
    fun `convertToRegex foo slash single star slash double star`() {
        assertEquals("foo/[^/]*/.*", convertToRegex("foo/*/**"))
    }

    @Test
    fun `convertToRegex double star slash single star slash`() {
        assertEquals("(?:.*/)?[^/]*/", convertToRegex("**/*/"))
    }

    @Test
    fun `convertToRegex single star slash double star slash`() {
        assertEquals("[^/]*/(?:.*/)?", convertToRegex("*/**/"))
    }

    @Test
    fun `convertToRegex double star slash single star slash foo`() {
        assertEquals("(?:.*/)?[^/]*/foo", convertToRegex("**/*/foo"))
    }

    @Test
    fun `convertToRegex single star slash double star slash foo`() {
        assertEquals("[^/]*/(?:.*/)?foo", convertToRegex("*/**/foo"))
    }

    @Test
    fun `convertToRegex slash double star slash single star slash`() {
        assertEquals("/(?:.*/)?[^/]*/", convertToRegex("/**/*/"))
    }

    @Test
    fun `convertToRegex slash single star slash double star slash`() {
        assertEquals("/[^/]*/(?:.*/)?", convertToRegex("/*/**/"))
    }

    @Test
    fun `convertToRegex foo slash double star slash single star slash`() {
        assertEquals("foo/(?:.*/)?[^/]*/", convertToRegex("foo/**/*/"))
    }

    @Test
    fun `convertToRegex foo slash single star slash double star slash`() {
        assertEquals("foo/[^/]*/(?:.*/)?", convertToRegex("foo/*/**/"))
    }

    @Test
    fun `convertToRegex slash double star slash single star slash foo`() {
        assertEquals("/(?:.*/)?[^/]*/foo", convertToRegex("/**/*/foo"))
    }

    @Test
    fun `convertToRegex slash single star slash double star slash foo`() {
        assertEquals("/[^/]*/(?:.*/)?foo", convertToRegex("/*/**/foo"))
    }

    @Test
    fun `convertToRegex foo slash double star slash single star slash bar`() {
        assertEquals("foo/(?:.*/)?[^/]*/bar", convertToRegex("foo/**/*/bar"))
    }

    @Test
    fun `convertToRegex foo slash single star slash double star slash bar`() {
        assertEquals("foo/[^/]*/(?:.*/)?bar", convertToRegex("foo/*/**/bar"))
    }
}