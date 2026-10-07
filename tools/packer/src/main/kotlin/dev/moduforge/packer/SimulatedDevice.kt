package dev.moduforge.packer

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityNotGrantedException
import dev.moduforge.sdk.DeviceGateway
import dev.moduforge.sdk.DeviceServiceCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * A pretend phone for `mfrg run`: the device calls of a script succeed and are printed, so that a
 * scenario can be written and its logic tried on a computer. Nothing is touched: apps are a fixed
 * list, taps are lines of text, a photo is a grey picture.
 *
 * @param holds whether the module may use a capability, the same rule as for every other service.
 * @param store writes a file into the module's storage.
 */
internal class SimulatedDevice(
    private val holds: (Capability) -> Boolean,
    private val store: (path: String, data: ByteArray) -> Unit,
    private val print: (String) -> Unit,
) : DeviceGateway {

    private val apps = listOf(
        mapOf("package" to "com.example.browser", "name" to "Browser"),
        mapOf("package" to "com.example.calculator", "name" to "Calculator"),
        mapOf("package" to "com.example.camera", "name" to "Camera"),
    )

    override suspend fun call(service: String, method: String, argsJson: String): String {
        val capability = DeviceServiceCatalog.capabilityOf(service) ?: throw IOException("there is no device service '$service'")
        if (!holds(capability)) throw CapabilityNotGrantedException(capability)
        val args = Json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        fun text(name: String) = (args[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun note(line: String) = print("[$service] $line")

        return when ("$service.$method") {
            "apps.list" -> apps.joinToString(",", "[", "]") { """{"package":"${it["package"]}","name":"${it["name"]}"}""" }
            "apps.launch" -> {
                val found = apps.firstOrNull { it["package"] == text("app") || it["name"].equals(text("app"), true) }
                    ?: throw IOException("no app is called \"${text("app")}\"")
                note("launch ${found["name"]}")
                OK
            }
            "apps.open" -> OK.also { note("open ${text("url")}") }
            "apps.installed" -> apps.any { it["package"] == text("package") }.toString()
            "screen.info" -> """{"width":1080,"height":2400,"package":"com.example.simulated","enabled":true}"""
            "screen.tap", "screen.press" -> OK.also { note("$method ${text("x")},${text("y")}") }
            "screen.swipe" -> OK.also { note("swipe ${text("x1")},${text("y1")} -> ${text("x2")},${text("y2")}") }
            "screen.back", "screen.home", "screen.recents", "screen.notifications" -> OK.also { note(method) }
            "screen.click" -> OK.also { note("click \"${text("text")}\"") }
            "screen.type" -> OK.also { note("type \"${text("text")}\"") }
            "screen.texts", "screen.find" -> "[]"
            "screen.wait" -> """{"found":false}""".also { note("wait for \"${text("text")}\" (a computer shows no screen)") }
            "screen.event" -> "null"
            "camera.list" -> """[{"lens":"back","id":"0"},{"lens":"front","id":"1"}]"""
            "camera.photo" -> {
                val lens = text("lens").ifEmpty { "back" }
                val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB).apply {
                    val g = createGraphics()
                    g.color = java.awt.Color.GRAY
                    g.fillRect(0, 0, width, height)
                    g.dispose()
                }
                val out = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
                store(text("path"), out)
                note("photo with the $lens camera saved as ${text("path")} (a grey picture)")
                """{"ok":true,"path":"${text("path")}","width":640,"height":480,"bytes":${out.size}}"""
            }
            else -> throw IOException("$service has no call named '$method'")
        }
    }

    private companion object {
        const val OK = """{"ok":true}"""
    }
}
