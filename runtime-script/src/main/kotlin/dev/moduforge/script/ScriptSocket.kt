package dev.moduforge.script

import dev.moduforge.sdk.Connection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A connection a script keeps open. Incoming bytes are collected by a background thread, so
 * reads can wait with a timeout and a script can interleave reading with other work.
 * A read returns null once the peer has closed and everything was consumed.
 */
internal class ScriptSocket(private val connection: Connection) {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var buffer = ByteArray(0)
    private var ended = false

    @Volatile
    private var closed = false

    init {
        thread(name = "script-socket", isDaemon = true) {
            try {
                val block = ByteArray(16 * 1024)
                while (true) {
                    val read = connection.input.read(block)
                    if (read < 0) break
                    if (read > 0) chunks.put(block.copyOf(read))
                }
            } catch (e: IOException) {
                // Reported to the reader as end of stream.
            } finally {
                chunks.put(END)
            }
        }
    }

    /**
     * Up to [max] bytes, as soon as any are available.
     *
     * @param timeoutSeconds null waits without limit.
     * @throws HostFailure with message `timeout` when nothing arrived in time.
     */
    fun read(max: Int, timeoutSeconds: Double?): ByteArray? {
        require(max > 0) { "byte count must be positive" }
        if (buffer.isEmpty() && !fill(deadline(timeoutSeconds))) return null
        return take(minOf(max, buffer.size))
    }

    /** Exactly [count] bytes, or null when the stream ends first. */
    fun readExactly(count: Int, timeoutSeconds: Double?): ByteArray? {
        require(count >= 0) { "byte count must not be negative" }
        val deadline = deadline(timeoutSeconds)
        while (buffer.size < count) if (!fill(deadline)) return null
        return take(count)
    }

    /** One line without its line break, or null at end of stream. A final line without a break is returned too. */
    fun readLine(timeoutSeconds: Double?): String? {
        val deadline = deadline(timeoutSeconds)
        while (true) {
            val newline = buffer.indexOf('\n'.code.toByte())
            if (newline >= 0) return take(newline + 1).decodeToString().trimEnd('\n', '\r')
            if (buffer.size > MAX_LINE_BYTES) throw HostFailure("line is too long")
            if (!fill(deadline)) return if (buffer.isEmpty()) null else take(buffer.size).decodeToString()
        }
    }

    fun write(data: ByteArray) {
        if (closed) throw HostFailure("connection is closed")
        try {
            connection.output.write(data)
            connection.output.flush()
        } catch (e: IOException) {
            throw HostFailure("connection is closed")
        }
    }

    fun close() {
        closed = true
        connection.close()
    }

    /** Appends the next chunk to [buffer]; false at end of stream. */
    private fun fill(deadlineNanos: Long?): Boolean {
        if (ended) return false
        val chunk = if (deadlineNanos == null) {
            chunks.take()
        } else {
            chunks.poll(maxOf(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS) ?: throw HostFailure("timeout")
        }
        if (chunk === END) {
            ended = true
            return false
        }
        buffer += chunk
        return true
    }

    private fun take(count: Int): ByteArray {
        val head = buffer.copyOfRange(0, count)
        buffer = buffer.copyOfRange(count, buffer.size)
        return head
    }

    private fun deadline(timeoutSeconds: Double?): Long? =
        timeoutSeconds?.let { System.nanoTime() + (it * 1_000_000_000).toLong().coerceAtLeast(0) }

    private companion object {
        val END = ByteArray(0)
        const val MAX_LINE_BYTES = 1024 * 1024
    }
}

/** A message received from a WebSocket. */
internal class WebSocketMessage(val text: Boolean, val data: ByteArray)

/**
 * WebSocket client (RFC 6455) over a [ScriptSocket]. Pings are answered and fragmented
 * messages reassembled transparently.
 */
internal class ScriptWebSocket private constructor(private val socket: ScriptSocket, private val host: ScriptHost) {

    private var open = true

    fun sendText(text: String) = send(OP_TEXT, text.toByteArray())

    fun sendBinary(data: ByteArray) = send(OP_BINARY, data)

    fun ping() = send(OP_PING, ByteArray(0))

    /**
     * Next message, or null when the connection was closed.
     *
     * @throws HostFailure with message `timeout` when nothing arrived in time.
     */
    fun receive(timeoutSeconds: Double?): WebSocketMessage? {
        val message = ByteArrayOutputStream()
        var text = false
        while (open) {
            val head = socket.readExactly(2, timeoutSeconds) ?: break
            val final = head[0].toInt() and 0x80 != 0
            val opcode = head[0].toInt() and 0x0F
            var length = (head[1].toInt() and 0x7F).toLong()
            if (length == 126L) {
                val extended = socket.readExactly(2, timeoutSeconds) ?: break
                length = ((extended[0].toLong() and 0xFF) shl 8) or (extended[1].toLong() and 0xFF)
            } else if (length == 127L) {
                val extended = socket.readExactly(8, timeoutSeconds) ?: break
                length = extended.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
            }
            if (length < 0 || message.size() + length > MAX_MESSAGE_BYTES) throw HostFailure("message is too large")
            val payload = socket.readExactly(length.toInt(), timeoutSeconds) ?: break
            when (opcode) {
                OP_PING -> send(OP_PONG, payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    runCatching { send(OP_CLOSE, payload.copyOf(minOf(2, payload.size))) }
                    break
                }
                OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                    if (opcode != OP_CONTINUATION) text = opcode == OP_TEXT
                    message.write(payload)
                    if (final) return WebSocketMessage(text, message.toByteArray())
                }
                else -> throw HostFailure("unexpected WebSocket frame")
            }
        }
        close()
        return null
    }

    fun close() {
        if (open) {
            open = false
            runCatching { frame(OP_CLOSE, ByteArray(0)) }
        }
        socket.close()
    }

    private fun send(opcode: Int, payload: ByteArray) {
        if (!open) throw HostFailure("connection is closed")
        frame(opcode, payload)
    }

    /** Writes one final frame. Client frames are masked, as the protocol requires. */
    private fun frame(opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65536 -> {
                out.write(0x80 or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) out.write((payload.size.toLong() shr shift).toInt() and 0xFF)
            }
        }
        val mask = host.randomBytes(4)
        out.write(mask)
        payload.forEachIndexed { index, byte -> out.write(byte.toInt() xor mask[index % 4].toInt()) }
        socket.write(out.toByteArray())
    }

    companion object {
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
        private const val HANDSHAKE_TIMEOUT_SECONDS = 20.0
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Performs the opening handshake on a connected socket. */
        fun open(socket: ScriptSocket, uri: URI, headers: Map<String, String>, host: ScriptHost): ScriptWebSocket {
            (headers.keys + headers.values).firstOrNull { '\r' in it || '\n' in it }?.let { throw HostFailure("invalid header") }
            val key = host.base64Encode(host.randomBytes(16), urlSafe = false)
            val request = buildString {
                append("GET ").append(uri.rawPath.orEmpty().ifEmpty { "/" })
                uri.rawQuery?.let { append('?').append(it) }
                append(" HTTP/1.1\r\n")
                append("Host: ").append(if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host).append("\r\n")
                append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            socket.write(request.toByteArray(Charsets.ISO_8859_1))

            val status = socket.readLine(HANDSHAKE_TIMEOUT_SECONDS) ?: throw HostFailure("connection closed during the handshake")
            if (status.split(' ').getOrNull(1) != "101") throw HostFailure("server refused the WebSocket: $status")
            var accept: String? = null
            while (true) {
                val line = socket.readLine(HANDSHAKE_TIMEOUT_SECONDS) ?: throw HostFailure("connection closed during the handshake")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                    accept = line.substring(colon + 1).trim()
                }
            }
            val expected = host.base64Encode(host.hash("sha1", (key + GUID).toByteArray()), urlSafe = false)
            if (accept != expected) throw HostFailure("server sent a wrong WebSocket handshake")
            return ScriptWebSocket(socket, host)
        }
    }
}
