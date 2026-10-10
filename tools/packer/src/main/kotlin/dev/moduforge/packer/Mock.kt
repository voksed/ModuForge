package dev.moduforge.packer

import dev.moduforge.sdk.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Canned answers for `mfrg run --mock <file.json>`: the module is tried without a network and
 * without a person at the keyboard.
 *
 * ```
 * {
 *   "http": [
 *     { "url": "api.example.com/items", "method": "GET", "status": 200, "body": "{\"n\": 3}" },
 *     { "url": "down.example.com", "status": 503 }
 *   ],
 *   "answers": ["Baku", "my-secret-token"]
 * }
 * ```
 *
 * A request is answered by the first entry whose `url` is contained in `host + path` (and whose `method`,
 * when given, matches); a request nothing matches gets 404 and is printed, so a missing entry is easy to see.
 */
internal class Mock private constructor(private val routes: List<Route>, private val answers: MutableList<String>) {

    private class Route(val url: String, val method: String?, val status: Int, val headers: Map<String, String>, val body: String)

    /** The next prepared answer to a question of the module, or null when none is left. */
    fun nextAnswer(): String? = synchronized(answers) { if (answers.isEmpty()) null else answers.removeAt(0) }

    /** A connection that behaves like a server holding the prepared answers. */
    fun connection(host: String, print: (String) -> Unit): Connection {
        val request = ByteArrayOutputStream()
        val reply = object {
            @Volatile
            var bytes: ByteArray? = null
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {
                request.write(b)
                tryAnswer()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                request.write(b, off, len)
                tryAnswer()
            }

            private fun tryAnswer() {
                if (reply.bytes != null) return
                val text = request.toString(Charsets.ISO_8859_1)
                val headEnd = text.indexOf("\r\n\r\n")
                if (headEnd < 0) return
                val head = text.substring(0, headEnd)
                val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (text.length - (headEnd + 4) < length) return
                val line = head.lineSequence().first().split(' ')
                val method = line.getOrElse(0) { "GET" }
                val path = line.getOrElse(1) { "/" }
                reply.bytes = answer(method, host + path, print)
            }
        }
        val input = object : InputStream() {
            private var source: InputStream? = null

            private fun source(): InputStream {
                source?.let { return it }
                // The client reads only after it has written the whole request.
                val bytes = reply.bytes ?: ByteArray(0)
                return ByteArrayInputStream(bytes).also { source = it }
            }

            override fun read(): Int = source().read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source().read(b, off, len)
        }
        return object : Connection {
            override val input: InputStream = input
            override val output: OutputStream = output
            override fun close() {}
        }
    }

    private fun answer(method: String, target: String, print: (String) -> Unit): ByteArray {
        val route = routes.firstOrNull { it.url in target && (it.method == null || it.method.equals(method, ignoreCase = true)) }
        if (route == null) {
            print("[mock] no answer prepared for $method $target")
            return response(404, emptyMap(), "no mock for $method $target")
        }
        print("[mock] $method $target -> ${route.status}")
        return response(route.status, route.headers, route.body)
    }

    private fun response(status: Int, headers: Map<String, String>, body: String): ByteArray {
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(" Mock\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Content-Length: ").append(bytes.size).append("\r\nConnection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    companion object {
        fun load(file: File): Mock {
            if (!file.isFile) throw UsageError("$file not found")
            val root = try {
                Json.parseToJsonElement(file.readText()) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw UsageError("$file is not a JSON object")
            val routes = (root["http"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { entry ->
                Route(
                    url = entry.text("url") ?: throw UsageError("a \"http\" entry of $file has no \"url\""),
                    method = entry.text("method"),
                    status = (entry["status"] as? JsonPrimitive)?.intOrNull ?: 200,
                    headers = (entry["headers"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() },
                    body = entry.text("body").orEmpty(),
                )
            }
            val answers = (root["answers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            return Mock(routes, answers.toMutableList())
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
