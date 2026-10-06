package dev.moduforge.script

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityNotGrantedException
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.TextStyle
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.TextStyle as NameStyle
import java.util.Base64
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A failure a script is expected to handle: a missing permission, a network error, bad input
 * from outside. Language bindings report it the way their language does (Lua: `nil, message`;
 * JavaScript: a thrown `Error`). Programming mistakes are reported as ordinary script errors.
 */
internal class HostFailure(message: String) : Exception(message)

/** One part of a `multipart/form-data` upload. */
internal class Upload(val field: String, val filename: String, val contentType: String, val content: ByteArray)

internal class HttpCall(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val form: Map<String, String> = emptyMap(),
    val files: List<Upload> = emptyList(),
    val redirects: Int = DEFAULT_REDIRECTS,
) {
    companion object {
        const val DEFAULT_REDIRECTS = 5
    }
}

/**
 * Everything a script can ask of the host, in language-neutral terms. Each language runtime
 * is a thin binding over this class, so Lua and JavaScript modules get the same API with the
 * same behaviour. All calls block the script thread; an interrupt (module stop) surfaces as
 * [InterruptedException].
 */
internal class ScriptHost(val context: ModuleContext, private val locale: Locale = Locale.getDefault()) {

    /** Interaction with the UI the script showed, waiting to be picked up by [uiNext]. */
    val uiEvents = LinkedBlockingQueue<UiEvent>()

    private val random = SecureRandom()

    // --- output, time ---------------------------------------------------------------------

    fun log(text: String) = context.log.info(text)

    fun time(): Double = System.currentTimeMillis() / 1000.0

    fun sleep(seconds: Double) = Thread.sleep((seconds * 1000).toLong().coerceAtLeast(0))

    /**
     * Formats a moment with `strftime`-style directives (`%Y %m %d %H %M %S %y %j %a %A %b %B
     * %p %Z %z %%`). A leading `!` selects UTC instead of the device time zone.
     */
    fun date(format: String?, epochSeconds: Double?): String {
        var pattern = format ?: "%Y-%m-%d %H:%M:%S"
        val utc = pattern.startsWith("!")
        if (utc) pattern = pattern.substring(1)
        val moment = moment(epochSeconds, utc)
        val out = StringBuilder()
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index++]
            if (char != '%' || index >= pattern.length) {
                out.append(char)
                continue
            }
            when (val directive = pattern[index++]) {
                'Y' -> out.append(moment.year)
                'y' -> out.append("%02d".format(moment.year % 100))
                'm' -> out.append("%02d".format(moment.monthValue))
                'd' -> out.append("%02d".format(moment.dayOfMonth))
                'H' -> out.append("%02d".format(moment.hour))
                'M' -> out.append("%02d".format(moment.minute))
                'S' -> out.append("%02d".format(moment.second))
                'j' -> out.append("%03d".format(moment.dayOfYear))
                'a' -> out.append(moment.dayOfWeek.getDisplayName(NameStyle.SHORT, locale))
                'A' -> out.append(moment.dayOfWeek.getDisplayName(NameStyle.FULL, locale))
                'b' -> out.append(moment.month.getDisplayName(NameStyle.SHORT, locale))
                'B' -> out.append(moment.month.getDisplayName(NameStyle.FULL, locale))
                'p' -> out.append(if (moment.hour < 12) "AM" else "PM")
                'Z' -> out.append(moment.zone.getDisplayName(NameStyle.SHORT, locale))
                'z' -> out.append(moment.offset.id.replace(":", "").let { if (it == "Z") "+0000" else it })
                '%' -> out.append('%')
                else -> out.append('%').append(directive)
            }
        }
        return out.toString()
    }

    /** Calendar fields of a moment: year, month (1-12), day, hour, min, sec, wday (1 = Sunday), yday. */
    fun dateFields(epochSeconds: Double?, utc: Boolean): Map<String, Int> {
        val moment = moment(epochSeconds, utc)
        return linkedMapOf(
            "year" to moment.year,
            "month" to moment.monthValue,
            "day" to moment.dayOfMonth,
            "hour" to moment.hour,
            "min" to moment.minute,
            "sec" to moment.second,
            "wday" to moment.dayOfWeek.value % 7 + 1,
            "yday" to moment.dayOfYear,
        )
    }

    private fun moment(epochSeconds: Double?, utc: Boolean): ZonedDateTime =
        Instant.ofEpochMilli(((epochSeconds ?: time()) * 1000).toLong())
            .atZone(if (utc) ZoneOffset.UTC else ZoneId.systemDefault())

    // --- permissions ----------------------------------------------------------------------

    /** @return granted, and the denial reason when not. */
    fun request(capability: String, reason: String, target: String?): Pair<Boolean, String?> {
        val parsed = capability(capability) ?: return false to "UNKNOWN_CAPABILITY"
        return when (val result = runBlocking { context.capabilities.request(CapabilityRequest(parsed, reason, target)) }) {
            CapabilityResult.Granted -> true to null
            is CapabilityResult.Denied -> false to result.reason.name
        }
    }

    fun granted(capability: String, target: String?): Boolean {
        val parsed = capability(capability) ?: return false
        return runBlocking { context.capabilities.isGranted(parsed, target) }
    }

    private fun capability(name: String): Capability? = Capability.entries.firstOrNull { it.name == name }

    // --- network --------------------------------------------------------------------------

    fun http(call: HttpCall): SimpleHttp.Response = guarded {
        var headers = call.headers
        var body = call.body
        if (call.files.isNotEmpty()) {
            val boundary = "mf" + hexEncode(randomBytes(12))
            body = multipart(boundary, call.form, call.files)
            headers = headers + ("Content-Type" to "multipart/form-data; boundary=$boundary")
        } else if (call.form.isNotEmpty() && body == null) {
            body = call.form.entries.joinToString("&") { urlencode(it.key) + "=" + urlencode(it.value) }.toByteArray()
            headers = headers + ("Content-Type" to "application/x-www-form-urlencoded")
        }
        val method = if (body != null && call.method.equals("GET", true)) "POST" else call.method
        SimpleHttp.request(context.network, method, call.url, headers, body, call.redirects.coerceIn(0, MAX_REDIRECTS))
    }

    private fun multipart(boundary: String, form: Map<String, String>, files: List<Upload>): ByteArray {
        fun quoted(text: String) = text.replace("\\", "\\\\").replace("\"", "%22").replace("\r", "").replace("\n", "")
        val out = ByteArrayOutputStream()
        fun line(text: String) = out.write("$text\r\n".toByteArray())
        form.forEach { (name, value) ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${quoted(name)}\"")
            line("")
            line(value)
        }
        files.forEach { file ->
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${quoted(file.field)}\"; filename=\"${quoted(file.filename)}\"")
            line("Content-Type: ${quoted(file.contentType)}")
            line("")
            out.write(file.content)
            line("")
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    /** Opens a raw connection that stays open until closed. */
    fun connect(host: String, port: Int, tls: Boolean): ScriptSocket =
        guarded { ScriptSocket(context.network.connect(host, port, tls)) }

    /** Opens a WebSocket (`ws://` or `wss://`). */
    fun websocket(url: String, headers: Map<String, String>): ScriptWebSocket {
        val uri = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            throw HostFailure("malformed URL")
        }
        val tls = when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> false
            else -> throw HostFailure("only ws and wss URLs are supported")
        }
        val host = uri.host ?: throw HostFailure("URL has no host")
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
        val socket = connect(host, port, tls)
        return try {
            ScriptWebSocket.open(socket, uri, headers, this)
        } catch (e: HostFailure) {
            socket.close()
            throw e
        }
    }

    // --- storage, notifications, questions ---------------------------------------------------

    fun storageRead(path: String): ByteArray? = guarded { context.storage.read(path) }

    fun storageWrite(path: String, data: ByteArray) = guarded { context.storage.write(path, data) }

    fun storageDelete(path: String): Boolean = guarded { context.storage.delete(path) }

    fun storageList(): List<String> = guarded { context.storage.list() }

    fun notify(title: String, text: String) = guarded { context.notifications.notify(title, text) }

    fun ask(question: String, secret: Boolean): String? = runBlocking { context.prompt.ask(question, secret) }

    // --- encodings, hashes ------------------------------------------------------------------

    fun urlencode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** @param algorithm `md5`, `sha1`, `sha256` or `sha512`. */
    fun hash(algorithm: String, data: ByteArray): ByteArray = try {
        MessageDigest.getInstance(digestName(algorithm)).digest(data)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalArgumentException("unknown hash algorithm '$algorithm'")
    }

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
        val name = "Hmac" + digestName(algorithm).replace("-", "")
        return try {
            Mac.getInstance(name).run {
                // An empty key is valid for HMAC but rejected by SecretKeySpec.
                init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, name))
                doFinal(data)
            }
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("unknown hash algorithm '$algorithm'")
        }
    }

    private fun digestName(algorithm: String): String = when (algorithm.lowercase().replace("-", "")) {
        "md5" -> "MD5"
        "sha1" -> "SHA-1"
        "sha256" -> "SHA-256"
        "sha512" -> "SHA-512"
        else -> throw IllegalArgumentException("unknown hash algorithm '$algorithm'")
    }

    fun base64Encode(data: ByteArray, urlSafe: Boolean): String =
        if (urlSafe) Base64.getUrlEncoder().withoutPadding().encodeToString(data) else Base64.getEncoder().encodeToString(data)

    /** Accepts the standard and the URL-safe alphabet, with or without padding. */
    fun base64Decode(text: String): ByteArray = try {
        Base64.getDecoder().decode(text.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) })
    } catch (e: IllegalArgumentException) {
        throw HostFailure("not base64 text")
    }

    fun hexEncode(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    fun hexDecode(text: String): ByteArray {
        val clean = text.trim()
        if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) throw HostFailure("not hexadecimal text")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun randomBytes(count: Int): ByteArray {
        require(count in 0..MAX_RANDOM_BYTES) { "random byte count must be 0..$MAX_RANDOM_BYTES" }
        return ByteArray(count).also(random::nextBytes)
    }

    // --- interface --------------------------------------------------------------------------

    /**
     * Shows a UI described with plain maps and lists: a list is a column; a map has `type`
     * (`column`, `row`, `text`, `button`, `field`) and the fields of that node. Null clears the UI.
     */
    fun uiShow(tree: Any?) {
        if (tree == null) context.ui.clear() else context.ui.show(node(tree, 0))
    }

    /** Next interaction with the shown UI as a map, or null when [timeoutSeconds] passes first. */
    fun uiNext(timeoutSeconds: Double?): Map<String, String>? {
        val event = if (timeoutSeconds == null) {
            uiEvents.take()
        } else {
            uiEvents.poll((timeoutSeconds * 1000).toLong().coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } ?: return null
        return when (event) {
            is UiEvent.Click -> mapOf("type" to "click", "id" to event.id)
            is UiEvent.TextChanged -> mapOf("type" to "text", "id" to event.id, "value" to event.value)
        }
    }

    private fun node(value: Any?, depth: Int): UiNode {
        require(depth <= MAX_UI_DEPTH) { "interface is nested too deeply" }
        if (value is List<*>) return UiNode.Column(value.map { node(it, depth + 1) })
        if (value is String) return UiNode.Text(value)
        require(value is Map<*, *>) { "an interface element must be a table with a 'type'" }
        fun text(key: String, default: String? = null): String =
            value[key]?.toString() ?: default ?: throw IllegalArgumentException("interface element '${value["type"]}' needs '$key'")
        fun children(): List<UiNode> = (value["children"] as? List<*>).orEmpty().map { node(it, depth + 1) }
        return when (val type = value["type"]?.toString()) {
            "column" -> UiNode.Column(children())
            "row" -> UiNode.Row(children())
            "text" -> UiNode.Text(
                text("text"),
                TextStyle.entries.firstOrNull { it.name.equals(value["style"]?.toString(), ignoreCase = true) } ?: TextStyle.BODY,
            )
            "button" -> UiNode.Button(text("id"), text("label"), value["enabled"] != false)
            "field" -> UiNode.TextField(text("id"), text("value", ""), text("label", ""))
            else -> throw IllegalArgumentException("unknown interface element type '$type'")
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    /** Runs a host service call, turning its expected failures into [HostFailure]. */
    private fun <T> guarded(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: CapabilityNotGrantedException) {
        throw HostFailure("${e.capability.name} is not granted")
    } catch (e: IOException) {
        throw HostFailure(e.message ?: "I/O error")
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        const val MAX_RANDOM_BYTES = 1024
        const val MAX_UI_DEPTH = 32
    }
}
