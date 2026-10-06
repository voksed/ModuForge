package dev.moduforge.core.authoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One value a module keeps through its `config` library and has described to the user.
 *
 * @property value current value as text; null when nothing is stored.
 */
data class ModuleSetting(val key: String, val label: String, val secret: Boolean, val value: String?)

/**
 * Reads and changes the settings a script module keeps in its storage.
 *
 * The `config` library stores values in [VALUES_FILE] and, for every value it asks the user
 * about, a description in [DESCRIPTIONS_FILE]. Only described values are settings; the rest
 * of the file is the module's own state.
 */
object ModuleSettings {
    const val VALUES_FILE = "config.json"
    const val DESCRIPTIONS_FILE = "config.meta.json"
    private const val ORDER = "_order"

    /** Settings in the order the module described them. Malformed files yield no settings. */
    fun list(values: String?, descriptions: String?): List<ModuleSetting> {
        val stored = parse(values)
        val described = parse(descriptions)
        // Lua tables have no key order, so its library lists the keys separately.
        val order = (described[ORDER] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        return (order + described.keys).distinct().mapNotNull { key ->
            val fields = described[key] as? JsonObject ?: return@mapNotNull null
            val value = stored[key]
            // Tables and lists are the module's own structures, not something to type into a field.
            if (value != null && value !is JsonPrimitive) return@mapNotNull null
            ModuleSetting(
                key = key,
                label = (fields["label"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: key,
                secret = (fields["secret"] as? JsonPrimitive)?.booleanOrNull == true,
                value = (value as? JsonPrimitive)?.takeUnless { it.content == "null" && !it.isString }?.content,
            )
        }
    }

    /**
     * Content of [VALUES_FILE] with [key] set to [text].
     *
     * A blank text removes the value, so the module asks for it again or falls back to its
     * default. A number or a boolean stays one when the new text still reads as such.
     */
    fun update(values: String?, key: String, text: String?): String {
        val stored = parse(values).toMutableMap()
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            stored.remove(key)
        } else {
            stored[key] = retype(stored[key] as? JsonPrimitive, trimmed)
        }
        return JsonObject(stored).toString()
    }

    private fun retype(previous: JsonPrimitive?, text: String): JsonPrimitive {
        if (previous == null || previous.isString) return JsonPrimitive(text)
        if (previous.booleanOrNull != null) text.toBooleanStrictOrNull()?.let { return JsonPrimitive(it) }
        if (previous.longOrNull != null) text.toLongOrNull()?.let { return JsonPrimitive(it) }
        if (previous.doubleOrNull != null) text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return JsonPrimitive(it) }
        return JsonPrimitive(text)
    }

    private fun parse(text: String?): Map<String, JsonElement> = try {
        (Json.parseToJsonElement(text.orEmpty().ifBlank { "{}" }) as? JsonObject).orEmpty()
    } catch (_: Exception) {
        emptyMap()
    }
}
