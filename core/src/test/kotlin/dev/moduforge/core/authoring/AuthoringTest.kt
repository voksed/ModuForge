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
    fun `JavaScript is analysed with its own comment and require syntax`() {
        val js = dev.moduforge.sdk.ModuleRuntimeKind.JS
        assertEquals(
            setOf(Capability.NETWORK_OUTBOUND, Capability.FILE_SANDBOXED),
            ScriptAnalyzer.detectPermissions("var t = require('mf/telegram');", js),
        )
        assertEquals(setOf(Capability.NETWORK_OUTBOUND), ScriptAnalyzer.detectPermissions("""mf.websocket("wss://x"); // mf.notify""", js))
        assertEquals(setOf(Capability.NETWORK_OUTBOUND), ScriptAnalyzer.detectPermissions("""var u = "https://a.example/"; mf.http({url: u});""", js))
        assertEquals(emptySet<Capability>(), ScriptAnalyzer.detectPermissions("// mf.http({})\nvar a = 1 - -1;", js))
        assertTrue(ScriptAnalyzer.usesInterface("mf.ui.show([])", js))
        assertTrue(!ScriptAnalyzer.usesInterface("-- mf.ui.show{}"))
    }

    @Test
    fun `a script with an interface is declared as having one`() {
        val manifest = LocalModules.manifest("local.a", "A", "mf.ui.show({})", emptySet(), null)
        assertEquals(dev.moduforge.sdk.UiKind.COMPOSE, manifest.ui)
        assertEquals(dev.moduforge.sdk.ModuleRuntimeKind.JS, LocalModules.runtimeForFile("Bot.JS"))
        assertEquals(null, LocalModules.runtimeForFile("notes.txt"))
    }

    @Test
    fun `calls inside comments do not count`() {
        assertEquals(emptySet<Capability>(), ScriptAnalyzer.detectPermissions("-- mf.http{}\nmf.log('x') -- mf.notify"))
    }

    @Test
    fun `every template declares what its script uses and yields a valid manifest`() {
        assertEquals(6, ModuleTemplates.ALL.map { it.key }.toSet().size)
        ModuleTemplates.ALL.forEach { template ->
            val detected = ScriptAnalyzer.detectPermissions(template.script, template.runtime)
            assertTrue("${template.key}: $detected", template.permissions.keys.containsAll(detected))
            val manifest = LocalModules.manifest("local.x", template.title, template.script, template.permissions.keys, null, template.runtime)
            assertTrue(template.key, ModuleManifests.validate(manifest).isEmpty())
            assertEquals(template.runtime, manifest.runtime)
            assertEquals(LocalModules.entryFor(template.runtime), manifest.entry)
        }
        LocalModules.RUNTIMES.forEach { runtime ->
            assertEquals(setOf("telegram", "watcher", "empty"), ModuleTemplates.forRuntime(runtime).map { it.kind }.toSet())
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

    @Test
    fun `device calls declare their capability and the background work they need`() {
        val camera = ScriptAnalyzer.detectPermissions("mf.camera.photo('a.jpg')", dev.moduforge.sdk.ModuleRuntimeKind.LUA)
        assertEquals(setOf(Capability.CAMERA), camera)
        val screen = ScriptAnalyzer.detectPermissions("mf.apps.launch('x');\nmf.screen.tap(1, 2);", dev.moduforge.sdk.ModuleRuntimeKind.JS)
        assertEquals(setOf(Capability.LAUNCH_APPS, Capability.SCREEN_CONTROL, Capability.BACKGROUND_EXECUTION), screen)
        assertEquals(emptySet<Capability>(), ScriptAnalyzer.detectPermissions("-- mf.screen.tap(1, 2)", dev.moduforge.sdk.ModuleRuntimeKind.LUA))
    }
}
