package dev.moduforge.script

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityGateway
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.Connection
import dev.moduforge.sdk.DenialReason
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ModuleLogger
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.NetworkGateway
import dev.moduforge.sdk.NotificationGateway
import dev.moduforge.sdk.StorageGateway
import dev.moduforge.sdk.UserPrompt
import dev.moduforge.sdk.ui.UiNode
import dev.moduforge.sdk.ui.UiSurface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Server side of a faked connection: runs on its own thread until it returns. */
typealias FakeServer = (host: String, port: Int, tls: Boolean, input: InputStream, output: OutputStream) -> Unit

/** In-memory host for running script modules in tests. */
class FakeHost(
    private val answers: MutableList<String?> = mutableListOf(),
    private val server: FakeServer? = null,
    private val declared: Set<Capability> = Capability.entries.toSet(),
) : ModuleContext {
    val lines = CopyOnWriteArrayList<String>()
    val files = mutableMapOf<String, ByteArray>()
    val questions = CopyOnWriteArrayList<String>()
    val shown = CopyOnWriteArrayList<UiNode?>()
    val connections = CopyOnWriteArrayList<String>()
    val finished = CountDownLatch(1)

    override val manifest = ModuleManifest("com.example.t", "T", "1.0.0", "1.0.0", "main.lua", ModuleRuntimeKind.LUA)
    override val capabilities = object : CapabilityGateway {
        override suspend fun request(request: CapabilityRequest) =
            if (request.capability in declared) CapabilityResult.Granted else CapabilityResult.Denied(DenialReason.NOT_DECLARED)

        override suspend fun isGranted(capability: Capability, target: String?) = capability in declared
    }
    override val log = object : ModuleLogger {
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += message }
        override fun error(message: String, cause: Throwable?) { lines += "ERROR $message" }
    }
    override val ui = object : UiSurface {
        override fun show(root: UiNode) { shown += root }
        override fun clear() { shown += null }
    }
    override val network = object : NetworkGateway {
        override suspend fun connect(host: String, port: Int, tls: Boolean): Connection {
            val handler = server ?: throw IOException("offline")
            connections += "$host:$port:$tls"
            val toServer = PipedOutputStream()
            val fromClient = PipedInputStream(toServer, 1 shl 20)
            val toClient = PipedOutputStream()
            val fromServer = PipedInputStream(toClient, 1 shl 20)
            thread(isDaemon = true) {
                try {
                    handler(host, port, tls, fromClient, toClient)
                } catch (e: IOException) {
                    // The client went away.
                } finally {
                    runCatching { toClient.close() }
                }
            }
            return object : Connection {
                override val input: InputStream = fromServer
                override val output: OutputStream = toServer
                override fun close() {
                    runCatching { toServer.close() }
                    runCatching { fromServer.close() }
                }
            }
        }
    }
    override val storage = object : StorageGateway {
        override suspend fun read(path: String) = files[path]
        override suspend fun write(path: String, data: ByteArray) { files[path] = data }
        override suspend fun delete(path: String) = files.remove(path) != null
        override suspend fun list() = files.keys.sorted()
    }
    val deviceCalls = CopyOnWriteArrayList<String>()
    override val device = object : dev.moduforge.sdk.DeviceGateway {
        override suspend fun call(service: String, method: String, argsJson: String): String {
            deviceCalls += "$service.$method $argsJson"
            if (service == "screen" && method == "back") throw IOException("accessibility service is off")
            return when ("$service.$method") {
                "apps.list" -> """[{"package":"com.example.app","name":"Example"}]"""
                "screen.info" -> """{"width":1080,"height":2400,"package":"com.example.app"}"""
                else -> """{"ok":true}"""
            }
        }
    }
    override val notifications = object : NotificationGateway {
        override suspend fun notify(title: String, text: String) { lines += "NOTIFY $title|$text" }
    }
    override val prompt = object : UserPrompt {
        override suspend fun ask(question: String, secret: Boolean): String? {
            questions += "$question|$secret"
            return answers.removeAt(0)
        }
    }

    override fun stopSelf(reason: String) {
        lines += "STOP $reason"
        finished.countDown()
    }

    /** Lines written by the script itself, without the runtime's closing lines. */
    val output: List<String> get() = lines.filter { it != "script finished" && !it.startsWith("STOP ") }
}

/** Runs a script to its end and returns the host it ran against. */
fun runScript(
    kind: ModuleRuntimeKind,
    script: String,
    host: FakeHost = FakeHost(),
    extraFiles: Map<String, String> = emptyMap(),
): FakeHost {
    val entry = if (kind == ModuleRuntimeKind.JS) "main.js" else "main.lua"
    val files = extraFiles.mapValues { it.value.toByteArray() } + (entry to script.trimIndent().toByteArray())
    val module = ScriptRuntimes.create(kind, files, entry)
    runBlocking { module.onStart(host) }
    assertTrue("script did not finish: ${host.lines}", host.finished.await(30, TimeUnit.SECONDS))
    return host
}

/** Reads one HTTP request: its head lines and its body (by Content-Length). */
fun readHttpRequest(input: InputStream): Pair<List<String>, ByteArray> {
    val head = ByteArrayOutputStream()
    while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
        val byte = input.read()
        if (byte < 0) throw IOException("closed")
        head.write(byte)
    }
    val lines = head.toString("ISO-8859-1").trim().split("\r\n")
    val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
    val body = ByteArray(length)
    var read = 0
    while (read < length) read += input.read(body, read, length - read).also { if (it < 0) throw IOException("closed") }
    return lines to body
}

fun OutputStream.respond(status: String, body: String = "", vararg headers: String) {
    val bytes = body.toByteArray()
    write(("HTTP/1.1 $status\r\n" + headers.joinToString("") { "$it\r\n" } + "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
    write(bytes)
    flush()
}
