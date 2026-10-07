package dev.moduforge.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The calls of the device services as the script languages see them: `mf.apps`, `mf.screen`,
 * `mf.camera`. Every call takes either positional arguments in the order of [Call.params] or a
 * single table/object with those names, so `mf.screen.tap(100, 200)` and
 * `mf.screen.tap{ x = 100, y = 200 }` are the same call.
 */
internal object DeviceApi {
    class Call(val name: String, val params: List<String> = emptyList())

    val services: Map<String, List<Call>> = linkedMapOf(
        "apps" to listOf(
            Call("list"),
            Call("launch", listOf("app")),
            Call("open", listOf("url")),
            Call("installed", listOf("package")),
        ),
        "screen" to listOf(
            Call("info"),
            Call("tap", listOf("x", "y", "ms")),
            Call("press", listOf("x", "y", "ms")),
            Call("swipe", listOf("x1", "y1", "x2", "y2", "ms")),
            Call("back"),
            Call("home"),
            Call("recents"),
            Call("notifications"),
            Call("texts"),
            Call("find", listOf("text")),
            Call("click", listOf("text")),
            Call("type", listOf("text")),
            Call("wait", listOf("text", "timeout")),
            Call("event", listOf("timeout")),
        ),
        "camera" to listOf(
            Call("list"),
            Call("photo", listOf("path", "lens", "size", "quality", "flash")),
        ),
    )

    /** JSON text of an argument map, as the bridge carries it. */
    fun encode(arguments: Map<String, Any?>): String = Json.encodeToString(JsonElement.serializer(), toJson(arguments))

    /** Plain Kotlin value (maps, lists, strings, numbers, booleans) of a JSON text. */
    fun decode(text: String): Any? = fromJson(Json.parseToJsonElement(text))

    /** Names positional [values] after [call]'s parameters; null values are left out. */
    fun named(call: Call, values: List<Any?>): Map<String, Any?> {
        require(values.size <= call.params.size) { "${call.name} takes at most ${call.params.size} arguments" }
        return call.params.zip(values).filter { it.second != null }.toMap()
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        // Sorted, so that the same call always produces the same text, whatever the language orders its tables by.
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) }.toSortedMap())
        is List<*> -> JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.entries.associate { it.key to fromJson(it.value) }
        is JsonArray -> element.map(::fromJson)
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            else -> element.doubleOrNull?.let { number -> if (number % 1.0 == 0.0 && Math.abs(number) < Int.MAX_VALUE) number.toInt() else number }
        }
    }
}
