package dev.moduforge.core.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ModulePackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = SigningKey.generate()

    private val manifest = """
        {"id":"com.example.bot","name":"Bot","version":"1.0.0","sdkRange":">=1.0.0 <2.0.0",
         "entry":"main.lua","runtime":"lua","permissions":["NETWORK_OUTBOUND"]}
    """.trimIndent()

    private val files = mapOf(
        "code/main.lua" to "print('hi')".toByteArray(),
        "code/lib/util.lua" to "return {}".toByteArray(),
        "data/config.json" to "{}".toByteArray(),
    )

    private fun pack(manifestJson: String = manifest, content: Map<String, ByteArray> = files, signer: SigningKey = key): File =
        temp.newFile().also { file -> file.outputStream().use { ModulePackageWriter.write(it, manifestJson, content, signer) } }

    /** Copies a package, letting [edit] replace (non-null) or drop (null) each entry and append new ones. */
    private fun repack(source: File, extra: Map<String, ByteArray> = emptyMap(), edit: (String, ByteArray) -> ByteArray?): File {
        val target = temp.newFile()
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                val entries = zip.entries().asSequence().mapNotNull { entry ->
                    edit(entry.name, zip.getInputStream(entry).readBytes())?.let { entry.name to it }
                } + extra.entries.map { it.key to it.value }
                entries.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return target
    }

    private fun assertInvalid(file: File, reasonPart: String) {
        val result = ModulePackageVerifier.verify(file)
        assertTrue("expected Invalid($reasonPart), got $result", result is PackageCheck.Invalid && reasonPart in result.reason)
    }

    @Test
    fun `signed package verifies and reports its signer`() {
        val result = ModulePackageVerifier.verify(pack()) as PackageCheck.Valid

        assertEquals("com.example.bot", result.manifest.id)
        assertEquals(key.fingerprint, result.signer)
        assertEquals(64, result.signer.length)
    }

    @Test
    fun `different keys give different signers`() {
        val other = ModulePackageVerifier.verify(pack(signer = SigningKey.generate())) as PackageCheck.Valid
        assertNotEquals(key.fingerprint, other.signer)
    }

    @Test
    fun `key survives encoding`() {
        assertEquals(key.fingerprint, SigningKey.decode(key.encode()).fingerprint)
        assertThrows(IllegalArgumentException::class.java) { SigningKey.decode("""{"privateKey":"AAAA","publicKey":"AAAA"}""") }
    }

    @Test
    fun `modified file is detected`() {
        val tampered = repack(pack()) { name, bytes -> if (name == "code/main.lua") "os.exit()".toByteArray() else bytes }
        assertInvalid(tampered, "signature does not match")
    }

    @Test
    fun `modified manifest is detected`() {
        val tampered = repack(pack()) { name, bytes ->
            if (name == ModulePackageFormat.MANIFEST) bytes.decodeToString().replace("NETWORK_OUTBOUND", "CLIPBOARD").toByteArray() else bytes
        }
        assertInvalid(tampered, "signature does not match")
    }

    @Test
    fun `added, removed and renamed files are detected`() {
        val original = pack()
        assertInvalid(repack(original, extra = mapOf("code/evil.lua" to ByteArray(1))) { _, bytes -> bytes }, "signature does not match")
        assertInvalid(repack(original) { name, bytes -> bytes.takeIf { name != "data/config.json" } }, "signature does not match")
        val renamed = repack(original, extra = mapOf("code/lib/other.lua" to files.getValue("code/lib/util.lua"))) { name, bytes ->
            bytes.takeIf { name != "code/lib/util.lua" }
        }
        assertInvalid(renamed, "signature does not match")
    }

    @Test
    fun `unsigned package is rejected`() {
        assertInvalid(repack(pack()) { name, bytes -> bytes.takeIf { name != ModulePackageFormat.SIGNATURE } }, "not signed")
    }

    @Test
    fun `files outside the package layout are rejected`() {
        listOf("../escape", "classes.dex", "code/../x", "/abs").forEach { name ->
            assertInvalid(repack(pack(), extra = mapOf(name to ByteArray(1))) { _, bytes -> bytes }, "unexpected file")
        }
    }

    @Test
    fun `non-archive is rejected`() {
        assertInvalid(temp.newFile().apply { writeText("hello") }, "not a module package")
    }

    @Test
    fun `writer refuses a package without its entry script`() {
        assertThrows(IllegalArgumentException::class.java) { pack(content = files - "code/main.lua") }
        assertThrows(IllegalArgumentException::class.java) { pack(content = files + ("secret.txt" to ByteArray(1))) }
        assertThrows(IllegalArgumentException::class.java) { pack(manifestJson = "{}") }
    }
}
