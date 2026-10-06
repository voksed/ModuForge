package dev.moduforge.script

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * JSON for Lua scripts.
 *
 * Decoding: objects and arrays become tables (arrays 1-based), `null` becomes `nil`.
 * Encoding: a table whose keys are exactly `1..n` becomes an array, any other table an object
 * with string keys; an empty table becomes `{}`. Whole numbers are written without a fraction.
 */
internal object LuaJson {
    private const val MAX_DEPTH = 64

    /** @throws LuaError when [text] is not JSON. */
    fun decode(text: String): LuaValue = try {
        toLua(Json.parseToJsonElement(text))
    } catch (e: SerializationException) {
        throw LuaError("invalid JSON: ${e.message?.lineSequence()?.firstOrNull()}")
    }

    /** @throws LuaError for values JSON cannot represent (functions, cycles, non-finite numbers). */
    fun encode(value: LuaValue): String = toJson(value, 0).toString()

    private fun toLua(element: JsonElement): LuaValue = when (element) {
        JsonNull -> LuaValue.NIL
        is JsonPrimitive -> when {
            element.isString -> LuaValue.valueOf(element.content)
            element.booleanOrNull != null -> LuaValue.valueOf(element.booleanOrNull!!)
            element.longOrNull?.let { it in Int.MIN_VALUE..Int.MAX_VALUE } == true -> LuaValue.valueOf(element.longOrNull!!.toInt())
            else -> LuaValue.valueOf(element.doubleOrNull ?: throw LuaError("invalid JSON number"))
        }
        is JsonArray -> LuaTable().also { table -> element.forEachIndexed { index, item -> table.set(index + 1, toLua(item)) } }
        is JsonObject -> LuaTable().also { table -> element.forEach { (key, item) -> table.set(key, toLua(item)) } }
    }

    private fun toJson(value: LuaValue, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) throw LuaError("value is nested too deeply or refers to itself")
        return when {
            value.isnil() -> JsonNull
            value.isboolean() -> JsonPrimitive(value.toboolean())
            value.type() == LuaValue.TNUMBER -> {
                val number = value.todouble()
                if (number.isNaN() || number.isInfinite()) throw LuaError("number cannot be written as JSON")
                if (number == Math.rint(number) && Math.abs(number) < 9.0e15) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
            }
            value.isstring() -> JsonPrimitive(value.tojstring())
            value.istable() -> {
                val table = value.checktable()
                val keys = table.keys()
                val length = table.length()
                if (keys.isNotEmpty() && keys.size == length && keys.all { it.isint() }) {
                    JsonArray((1..length).map { toJson(table.get(it), depth + 1) })
                } else {
                    JsonObject(keys.associate { it.tojstring() to toJson(table.get(it), depth + 1) })
                }
            }
            else -> throw LuaError("${value.typename()} cannot be written as JSON")
        }
    }
}
