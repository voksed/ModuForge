package dev.moduforge.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {

    private fun release(
        tag: String = "v0.2.0",
        body: String = "Notes",
        assets: String = """{"name":"ModuForge-0.2.0.apk","size":1000,"browser_download_url":"https://github.com/voksed/ModuForge/releases/download/v0.2.0/ModuForge-0.2.0.apk"}""",
        extra: String = "",
    ) = """{"tag_name":"$tag","body":${"\"" + body.replace("\n", "\\n") + "\""},"assets":[$assets]$extra}"""

    @Test
    fun `a newer release with an apk is an update`() {
        val update = AppUpdates.parse(release(), "0.1.1", "en")
        assertEquals(AppUpdate("0.2.0", "https://github.com/voksed/ModuForge/releases/download/v0.2.0/ModuForge-0.2.0.apk", 1000, "Notes"), update)
    }

    @Test
    fun `each edition takes its own apk`() {
        val base = "https://github.com/voksed/ModuForge/releases/download/v0.2.0/"
        val assets = """{"name":"ModuForge-0.2.0-full.apk","size":2,"browser_download_url":"${base}ModuForge-0.2.0-full.apk"},""" +
            """{"name":"ModuForge-0.2.0.apk","size":1,"browser_download_url":"${base}ModuForge-0.2.0.apk"}"""
        assertEquals("${base}ModuForge-0.2.0.apk", AppUpdates.parse(release(assets = assets), "0.1.1", "en")?.downloadUrl)
        assertEquals("${base}ModuForge-0.2.0-full.apk", AppUpdates.parse(release(assets = assets), "0.1.1", "en", full = true)?.downloadUrl)
    }

    @Test
    fun `the same, an older or an unreadable version is not an update`() {
        assertNull(AppUpdates.parse(release(tag = "v0.1.1"), "0.1.1", "en"))
        assertNull(AppUpdates.parse(release(tag = "v0.1.0"), "0.1.1", "en"))
        assertNull(AppUpdates.parse(release(tag = "nightly"), "0.1.1", "en"))
        assertNull(AppUpdates.parse(release(), "dev", "en"))
        assertNull(AppUpdates.parse("not json", "0.1.1", "en"))
        assertNull(AppUpdates.parse("[]", "0.1.1", "en"))
    }

    @Test
    fun `versions are compared as numbers, not as text`() {
        assertEquals("0.10.0", AppUpdates.parse(release(tag = "v0.10.0"), "0.9.5", "en")?.version)
        assertNull(AppUpdates.parse(release(tag = "v0.9.5"), "0.10.0", "en"))
        assertNull(AppUpdates.parse(release(tag = "v1.0.0-beta.1"), "1.0.0", "en"))
    }

    @Test
    fun `drafts and pre-releases are ignored`() {
        assertNull(AppUpdates.parse(release(extra = ""","draft":true"""), "0.1.1", "en"))
        assertNull(AppUpdates.parse(release(extra = ""","prerelease":true"""), "0.1.1", "en"))
    }

    @Test
    fun `only an apk served by github is accepted`() {
        val other = """{"name":"ModuForge.apk","size":1,"browser_download_url":"https://evil.example.org/ModuForge.apk"}"""
        val http = """{"name":"ModuForge.apk","size":1,"browser_download_url":"http://github.com/x/ModuForge.apk"}"""
        val zip = """{"name":"notes.zip","size":1,"browser_download_url":"https://github.com/x/notes.zip"}"""
        val tricky = """{"name":"a.apk","size":1,"browser_download_url":"https://github.com.evil.example.org/a.apk"}"""
        assertNull(AppUpdates.parse(release(assets = "$other,$http,$zip,$tricky"), "0.1.1", "en"))
        val good = """{"name":"ModuForge.APK","size":5,"browser_download_url":"https://objects.githubusercontent.com/x/ModuForge.APK"}"""
        assertEquals("https://objects.githubusercontent.com/x/ModuForge.APK", AppUpdates.parse(release(assets = "$other,$zip,$good"), "0.1.1", "en")?.downloadUrl)
        assertNull(AppUpdates.parse(release(assets = ""), "0.1.1", "en"))
    }

    @Test
    fun `an oversized apk is refused`() {
        val big = """{"name":"a.apk","size":${AppUpdates.MAX_APK_BYTES + 1},"browser_download_url":"https://github.com/x/a.apk"}"""
        assertNull(AppUpdates.parse(release(assets = big), "0.1.1", "en"))
    }

    @Test
    fun `download addresses are checked strictly`() {
        assertTrue(AppUpdates.isDownloadAddress("https://github.com/a/b/releases/download/v1/x.apk"))
        assertTrue(AppUpdates.isDownloadAddress("https://release-assets.githubusercontent.com/x"))
        assertFalse(AppUpdates.isDownloadAddress("https://user:pw@github.com/x"))
        assertFalse(AppUpdates.isDownloadAddress("https://notgithub.com/x"))
        assertFalse(AppUpdates.isDownloadAddress("file:///sdcard/x.apk"))
        assertFalse(AppUpdates.isDownloadAddress("not a url"))
    }

    @Test
    fun `the real answer of the release endpoint is understood`() {
        val real = javaClass.getResource("/github-latest-release.json")!!.readText()
        val update = AppUpdates.parse(real, "0.1.0", "en")
        assertEquals("0.1.1", update?.version)
        assertTrue(update?.downloadUrl, update?.downloadUrl == "https://github.com/voksed/ModuForge/releases/download/v0.1.1/ModuForge-0.1.1.apk")
        assertTrue(update!!.sizeBytes > 1_000_000)
        assertNull(AppUpdates.parse(real, "0.1.1", "en"))
    }

    @Test
    fun `release notes follow the language of the app`() {
        val body = "[[en]]\nEnglish notes\n- one\n\n[[ru]]\nРусские заметки\n"
        assertEquals("Русские заметки", AppUpdates.notesFor(body, "ru"))
        assertEquals("English notes\n- one", AppUpdates.notesFor(body, "EN"))
        assertEquals("English notes\n- one", AppUpdates.notesFor(body, "de"))
        assertEquals("Plain notes", AppUpdates.notesFor("  Plain notes \n", "ru"))
        assertEquals("Only Russian", AppUpdates.notesFor("[[ru]]\nOnly Russian", "de"))
        assertEquals("", AppUpdates.notesFor("", "en"))
    }
}
