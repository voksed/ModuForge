package dev.moduforge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManifestsTest {

    private val valid = """
        {
          "id": "com.example.mytool",
          "name": "My Tool",
          "version": "1.0.0",
          "sdkRange": ">=1.0.0 <2.0.0",
          "entry": "com.example.mytool.ToolModule",
          "permissions": ["NETWORK_OUTBOUND", "AI_INFERENCE"],
          "ui": "compose",
          "author": "Example",
          "description": "Does a thing.",
          "permissionReasons": {"NETWORK_OUTBOUND": "Talks to the API."}
        }
    """.trimIndent()

    @Test
    fun `parses a well-formed manifest`() {
        val manifest = (ModuleManifests.parse(valid) as ManifestResult.Valid).manifest
        assertEquals("com.example.mytool", manifest.id)
        assertEquals(listOf(Capability.NETWORK_OUTBOUND, Capability.AI_INFERENCE), manifest.permissions)
        assertEquals(UiKind.COMPOSE, manifest.ui)
    }

    @Test
    fun `permissions default to none`() {
        val json = """{"id":"a.b","name":"n","version":"1.0.0","sdkRange":"1.0.0","entry":"a.b.C"}"""
        val manifest = (ModuleManifests.parse(json) as ManifestResult.Valid).manifest
        assertTrue(manifest.permissions.isEmpty())
        assertEquals(UiKind.NONE, manifest.ui)
    }

    @Test
    fun `encode and parse round-trip`() {
        val manifest = (ModuleManifests.parse(valid) as ManifestResult.Valid).manifest
        assertEquals(ManifestResult.Valid(manifest), ModuleManifests.parse(ModuleManifests.encode(manifest)))
    }

    @Test
    fun `rejects unknown capability`() {
        assertInvalid(valid.replace("AI_INFERENCE", "ROOT_SHELL"))
    }

    @Test
    fun `rejects unknown keys`() {
        assertInvalid(valid.replace("\"author\"", "\"hiddenPermissions\""))
    }

    @Test
    fun `rejects non-JSON input`() {
        assertInvalid("not json")
    }

    @Test
    fun `reports every structural problem`() {
        val manifest = ModuleManifest(
            id = "NotReverseDns",
            name = " ",
            version = "1.0",
            sdkRange = "latest",
            entry = "NoPackage",
            permissions = listOf(Capability.CLIPBOARD, Capability.CLIPBOARD),
        )
        assertEquals(6, ModuleManifests.validate(manifest).size)
    }

    @Test
    fun `script runtimes take a relative path as entry`() {
        val script = ModuleManifest("a.b", "n", "1.0.0", "1.0.0", "bot/main.py", runtime = ModuleRuntimeKind.PYTHON)
        assertTrue(ModuleManifests.validate(script).isEmpty())
        listOf("../main.py", "/main.py", "bot//main.py", "bot/./main.py", "").forEach { entry ->
            assertEquals(entry, 1, ModuleManifests.validate(script.copy(entry = entry)).size)
        }
        assertEquals(1, ModuleManifests.validate(script.copy(runtime = ModuleRuntimeKind.DEX)).size)
    }

    @Test
    fun `reasons must refer to declared permissions`() {
        val manifest = ModuleManifest(
            "a.b", "n", "1.0.0", "1.0.0", "a.b.C",
            permissions = listOf(Capability.CLIPBOARD),
            permissionReasons = mapOf(Capability.NETWORK_OUTBOUND to "why"),
        )
        assertEquals(1, ModuleManifests.validate(manifest).size)
    }

    @Test
    fun `sdk compatibility follows the declared range`() {
        val manifest = (ModuleManifests.parse(valid) as ManifestResult.Valid).manifest
        assertTrue(ModuleManifests.supportsSdk(manifest, SemVer(1, 4, 0)))
        assertFalse(ModuleManifests.supportsSdk(manifest, SemVer(2, 0, 0)))
        assertTrue(ModuleManifests.supportsSdk(manifest))
    }

    private fun assertInvalid(json: String) {
        val result = ModuleManifests.parse(json)
        assertTrue("expected Invalid, got $result", result is ManifestResult.Invalid)
    }
}
