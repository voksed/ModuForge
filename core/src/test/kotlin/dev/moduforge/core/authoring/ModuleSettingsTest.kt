package dev.moduforge.core.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleSettingsTest {

    private val descriptions = """{"token":{"label":"Bot token","secret":true},"interval":{"label":"Seconds"},"mode":{}}"""

    @Test
    fun `lists described values only, in the order they were described`() {
        val settings = ModuleSettings.list("""{"interval":300,"token":"abc","offset":17}""", descriptions)
        assertEquals(
            listOf(
                ModuleSetting("token", "Bot token", secret = true, value = "abc"),
                ModuleSetting("interval", "Seconds", secret = false, value = "300"),
                ModuleSetting("mode", "mode", secret = false, value = null),
            ),
            settings,
        )
    }

    @Test
    fun `an explicit order wins over the order of the file`() {
        val ordered = """{"b":{"label":"B"},"a":{"label":"A"},"c":{"label":"C"},"_order":["a","b","missing"]}"""
        assertEquals(listOf("a", "b", "c"), ModuleSettings.list("{}", ordered).map { it.key })
    }

    @Test
    fun `skips structured values and tolerates broken files`() {
        assertEquals(listOf("interval", "mode"), ModuleSettings.list("""{"token":{"a":1}}""", descriptions).map { it.key })
        assertTrue(ModuleSettings.list("{}", "not json").isEmpty())
        assertTrue(ModuleSettings.list(null, null).isEmpty())
        assertEquals(3, ModuleSettings.list("broken", descriptions).size)
    }

    @Test
    fun `update keeps the kind of a value and the rest of the file`() {
        val values = """{"interval":300,"enabled":true,"token":"abc","offset":17}"""
        assertEquals("""{"interval":60,"enabled":true,"token":"abc","offset":17}""", ModuleSettings.update(values, "interval", " 60 "))
        assertEquals("""{"interval":"often","enabled":true,"token":"abc","offset":17}""", ModuleSettings.update(values, "interval", "often"))
        assertEquals("""{"interval":300,"enabled":false,"token":"abc","offset":17}""", ModuleSettings.update(values, "enabled", "false"))
        assertEquals("""{"interval":300,"enabled":true,"token":"123","offset":17}""", ModuleSettings.update(values, "token", "123"))
    }

    @Test
    fun `a blank value removes the setting and a new one is added as text`() {
        assertEquals("""{"offset":17}""", ModuleSettings.update("""{"token":"abc","offset":17}""", "token", "  "))
        assertEquals("""{"url":"https://example.org"}""", ModuleSettings.update(null, "url", "https://example.org"))
    }
}
