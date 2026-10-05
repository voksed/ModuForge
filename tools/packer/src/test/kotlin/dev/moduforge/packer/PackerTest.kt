package dev.moduforge.packer

import dev.moduforge.core.authoring.ModuleTemplates
import dev.moduforge.core.pkg.ModulePackageVerifier
import dev.moduforge.core.pkg.PackageCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class PackerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project(): File {
        val dir = temp.newFolder("bot")
        run(listOf("init", dir.path, "--id", "com.example.bot", "--runtime", "lua", "--entry", "main.lua"))
        File(dir, "main.lua").writeText("print('hi')")
        File(dir, "lib").mkdirs()
        File(dir, "lib/util.lua").writeText("return {}")
        return dir
    }

    private fun key(): File = File(temp.root, "key.json").also { run(listOf("keygen", it.path)) }

    @Test
    fun `init, keygen, pack and verify work together`() {
        val dir = project()
        val out = File(temp.root, "bot.mfrg")

        run(listOf("pack", dir.path, "--key", key().path, "--out", out.path))

        val check = ModulePackageVerifier.verify(out) as PackageCheck.Valid
        assertEquals("com.example.bot", check.manifest.id)
        assertTrue(check.signer in run(listOf("verify", out.path)))
        ZipFile(out).use { zip ->
            assertEquals(
                listOf("META-INF/MFRG.SIG", "code/lib/util.lua", "code/main.lua", "moduforge.json"),
                zip.entries().asSequence().map { it.name }.sorted().toList(),
            )
        }
    }

    @Test
    fun `credentials and caches stay out of the package`() {
        val dir = project()
        File(dir, "account.session").writeText("secret")
        File(dir, ".env").writeText("TOKEN=secret")
        File(dir, "__pycache__").mkdirs()
        File(dir, "__pycache__/x.pyc").writeText("cache")
        val out = File(temp.root, "bot.mfrg")

        val report = run(listOf("pack", dir.path, "--key", key().path, "--out", out.path))

        assertTrue(report, "account.session" in report && ".env" in report)
        ZipFile(out).use { zip ->
            assertTrue(zip.entries().asSequence().none { "session" in it.name || ".env" in it.name || "pycache" in it.name })
        }
    }

    private fun answers(vararg lines: String): () -> String? = lines.toMutableList().let { queue -> { queue.removeFirstOrNull() } }

    @Test
    fun `wizard creates a project that packs as it is`() {
        val key = key()
        ModuleTemplates.ALL.forEachIndexed { index, template ->
            val dir = File(temp.root, "wizard-${template.key}")
            val report = run(
                listOf("new", dir.path),
                answers("Price \"watch\" 2", "", "Ada", "Says \\ things", (index + 1).toString()),
            )
            assertTrue(report, dir.path in report)

            val out = File(temp.root, "${template.key}.mfrg")
            run(listOf("pack", dir.path, "--key", key.path, "--out", out.path))
            val manifest = (ModulePackageVerifier.verify(out) as PackageCheck.Valid).manifest
            assertEquals("my.pricewatch2", manifest.id)
            assertEquals("Price \"watch\" 2", manifest.name)
            assertEquals("Says \\ things", manifest.description)
            assertEquals(template.permissions.keys.toList(), manifest.permissions)
            assertEquals(template.permissions, manifest.permissionReasons)
        }
    }

    @Test
    fun `wizard accepts defaults at end of input and refuses to overwrite`() {
        val dir = File(temp.root, "defaults")
        run(listOf("new", dir.path), answers())
        assertTrue(File(dir, "moduforge.json").readText().contains("\"id\": \"my.mymodule\""))
        assertThrows(UsageError::class.java) { run(listOf("new", dir.path), answers()) }
        assertThrows(UsageError::class.java) { run(listOf("new", File(temp.root, "bad").path), answers("X", "Not An Id")) }
    }

    @Test
    fun `mistakes are reported as usage errors`() {
        val dir = project()
        val key = key()
        assertThrows(UsageError::class.java) { run(listOf("pack", dir.path, "--key", File(temp.root, "missing.json").path)) }
        assertThrows(UsageError::class.java) { run(listOf("keygen", key.path)) }
        File(dir, "main.lua").delete()
        assertThrows(UsageError::class.java) { run(listOf("pack", dir.path, "--key", key.path)) }
        assertThrows(UsageError::class.java) { run(listOf("init", temp.newFolder().path, "--id", "Bad", "--runtime", "lua", "--entry", "m.lua")) }
        assertThrows(UsageError::class.java) { run(listOf("verify", key.path)) }
    }
}
