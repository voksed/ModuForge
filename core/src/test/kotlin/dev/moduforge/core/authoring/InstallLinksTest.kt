package dev.moduforge.core.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLinksTest {

    private val signer = "ab".repeat(32)
    private val url = "https://example.org/files/my%20bot.mfrg?v=1&x=2"

    @Test
    fun `a created link parses back to the same address and signer`() {
        val link = InstallLinks.create(url, signer.uppercase().chunked(4).joinToString(" "))
        assertTrue(link, link.startsWith("moduforge://install?url=https%3A%2F%2Fexample.org"))
        assertEquals(InstallLink(url, signer), InstallLinks.parse(link))
        assertEquals(InstallLink(url, null), InstallLinks.parse(InstallLinks.create(url, null)))
    }

    @Test
    fun `only complete https links are accepted`() {
        assertNull(InstallLinks.parse("https://example.org/a.mfrg"))
        assertNull(InstallLinks.parse("moduforge://other?url=https%3A%2F%2Fexample.org%2Fa.mfrg"))
        assertNull(InstallLinks.parse("moduforge://install"))
        assertNull(InstallLinks.parse("moduforge://install?url=http%3A%2F%2Fexample.org%2Fa.mfrg"))
        assertNull(InstallLinks.parse("moduforge://install?url=https%3A%2F%2Fuser%3Apw%40example.org%2Fa.mfrg"))
        assertNull(InstallLinks.parse("moduforge://install?url=file%3A%2F%2F%2Fsdcard%2Fa.mfrg"))
        assertNull(InstallLinks.parse("moduforge://install?url=https%3A%2F%2Fexample.org%2Fa.mfrg&signer=abc"))
        assertNull(InstallLinks.parse("not a link at all"))
        assertThrows(IllegalArgumentException::class.java) { InstallLinks.create("http://example.org/a.mfrg", null) }
        assertThrows(IllegalArgumentException::class.java) { InstallLinks.create(url, "1234") }
    }

    @Test
    fun `the signer of the package is compared with the one in the link`() {
        val link = InstallLink(url, signer)
        assertTrue(InstallLinks.signerMatches(link, signer.uppercase()))
        assertFalse(InstallLinks.signerMatches(link, "cd".repeat(32)))
        assertTrue(InstallLinks.signerMatches(InstallLink(url, null), "cd".repeat(32)))
    }
}
