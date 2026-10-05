package dev.moduforge.core.authoring

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

class AuthoringTest {

    @Test
    fun `permissions are detected from the calls a script makes`() {
        assertEquals(emptySet<Capability>(), ScriptAnalyzer.detectPermissions("mf.log('hi')"))
        assertEquals(setOf(Capability.NETWORK_OUTBOUND), ScriptAnalyzer.detectPermissions("local r = mf.http{url=u}"))
        assertEquals(setOf(Capability.FILE_SANDBOXED), ScriptAnalyzer.detectPermissions("""local c = require("mf.config")"""))
        assertEquals(
            setOf(Capability.NETWORK_OUTBOUND, Capability.FILE_SANDBOXED),
            ScriptAnalyzer.detectPermissions("local t = require 'mf.telegram'"),
        )
        assertEquals(
            setOf(Capability.FILE_SANDBOXED, Capability.NOTIFICATIONS),
            ScriptAnalyzer.detectPermissions("mf.storage.write('a', 'b')\nmf . notify('x')"),
        )
    }

    @Test
    fun `calls inside comments do not count`() {
        assertEquals(emptySet<Capability>(), ScriptAnalyzer.detectPermissions("-- mf.http{}\nmf.log('x') -- mf.notify"))
    }

    @Test
    fun `every template declares what its script uses and yields a valid manifest`() {
        ModuleTemplates.ALL.forEach { template ->
            val detected = ScriptAnalyzer.detectPermissions(template.script)
            assertTrue("${template.key}: $detected", template.permissions.keys.containsAll(detected))
            val manifest = LocalModules.manifest("local.x", template.title, template.script, template.permissions.keys, null)
            assertTrue(template.key, ModuleManifests.validate(manifest).isEmpty())
        }
    }

    @Test
    fun `ids are valid, readable and unique`() {
        assertEquals("local.pricewatch", LocalModules.idFor("Price Watch!") { false })
        assertEquals("local.m2048", LocalModules.idFor("2048") { false })
        assertEquals("local.module", LocalModules.idFor("Мой бот") { false })
        assertEquals("local.bot3", LocalModules.idFor("bot") { it in setOf("local.bot", "local.bot2") })
    }

    @Test
    fun `manifest unions selected and detected permissions and advances the version`() {
        val first = LocalModules.manifest("local.a", " A ", "mf.http{}", setOf(Capability.BACKGROUND_EXECUTION, Capability.CLIPBOARD), null)
        assertEquals("A", first.name)
        assertEquals("1.0.0", first.version)
        assertEquals(listOf(Capability.NETWORK_OUTBOUND, Capability.BACKGROUND_EXECUTION), first.permissions)

        val second = LocalModules.manifest("local.a", "A", "mf.log('x')", emptySet(), first)
        assertEquals("1.0.1", second.version)
        assertTrue(second.permissions.isEmpty())
    }

    @Test
    fun `package holds the manifest and the script where the sandbox looks for them`() {
        val manifest = LocalModules.manifest("local.a", "A", "mf.log('x')", emptySet(), null)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(LocalModules.pack(manifest, "mf.log('x')").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(setOf("moduforge.json", "code/main.lua"), entries.keys)
        assertEquals("mf.log('x')", entries["code/main.lua"])
        assertEquals(manifest, (ModuleManifests.parse(entries.getValue("moduforge.json")) as dev.moduforge.sdk.ManifestResult.Valid).manifest)
    }
}
