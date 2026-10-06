package dev.moduforge.core.dev

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Wire format of "developer push": a computer sends a module package to the host, which
 * installs it, restarts the module and can stream its output back.
 *
 * ```
 * host:     MFPUSH1 <nonce>\n
 * computer: <length> <mac> <follow|once>\n  followed by <length> bytes of the package
 * host:     OK <message>\n  or  ERR <message>\n
 * host:     module output until the computer disconnects   (only after OK, with "follow")
 * ```
 *
 * The pairing token never travels: the computer proves it knows the token with an
 * HMAC-SHA256 over the nonce, the mode and the package. A nonce is used once, so a recorded
 * exchange cannot be replayed.
 */
object DevPush {
    const val DEFAULT_PORT = 47800
    const val MAX_PACKAGE_BYTES = 64 * 1024 * 1024
    internal const val GREETING = "MFPUSH1"
    internal const val FOLLOW = "follow"
    internal const val ONCE = "once"
    internal const val MAX_LINE_BYTES = 1024

    // Without the characters that are easy to confuse when typed from a screen.
    private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val TOKEN_LENGTH = 16

    /** A new pairing token, grouped for reading: `ABCD-EFGH-JKLM-NPQR`. */
    fun newToken(random: SecureRandom = SecureRandom()): String =
        (1..TOKEN_LENGTH).map { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] }
            .chunked(4).joinToString("-") { it.joinToString("") }

    /** The token as it enters the MAC: typed with or without dashes, in any case. */
    fun normalizeToken(text: String): String = text.uppercase().filter { it.isLetterOrDigit() }

    internal fun newNonce(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    internal fun mac(token: String, nonce: String, follow: Boolean, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalizeToken(token).toByteArray(), "HmacSHA256"))
        mac.update("$nonce ${if (follow) FOLLOW else ONCE}\n".toByteArray())
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** Reads up to a line break without consuming what follows it. */
    internal fun readLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("connection closed")
            if (byte == '\n'.code) return line.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (line.size() >= MAX_LINE_BYTES) throw IOException("line too long")
            line.write(byte)
        }
    }

    internal fun oneLine(text: String): String = text.replace(Regex("""\s*[\r\n]+\s*"""), "; ")
}

/**
 * Outcome of a push.
 *
 * @property moduleId module whose output can be followed; null when nothing was installed.
 */
data class PushReply(val ok: Boolean, val message: String, val moduleId: String? = null)

/** Accepts pushed packages. One instance serves one listening socket. */
class DevPushServer(private val token: () -> String, private val handler: Handler) : Closeable {

    interface Handler {
        /** Installs the package and restarts its module. Called on a connection thread. */
        fun install(packageBytes: ByteArray): PushReply

        /** Output the module produces from now on, as text ready to print. */
        fun output(moduleId: String): Flow<String>
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val active = AtomicInteger()
    private var listener: ServerSocket? = null

    /** Port the server listens on; 0 while it is stopped. */
    val port: Int get() = listener?.localPort ?: 0

    /**
     * @param port 0 picks a free one.
     * @param allInterfaces false accepts connections from this device only, which is what a
     *   USB connection forwarded by adb needs; true also accepts them from the local network.
     * @throws IOException when the port is taken.
     */
    @Synchronized
    fun start(port: Int = DevPush.DEFAULT_PORT, allInterfaces: Boolean = false) {
        check(listener == null) { "already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(if (allInterfaces) null else InetAddress.getLoopbackAddress(), port), BACKLOG)
        listener = socket
        thread(name = "dev-push-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                if (active.incrementAndGet() > MAX_CONNECTIONS) {
                    active.decrementAndGet()
                    client.close()
                    continue
                }
                connections += client
                thread(name = "dev-push-connection", isDaemon = true) {
                    try {
                        client.use(::serve)
                    } catch (e: IOException) {
                        // The computer went away; nothing to report to.
                    } finally {
                        connections -= client
                        active.decrementAndGet()
                    }
                }
            }
        }
    }

    /** Stops listening and drops every connection, including those following output. */
    @Synchronized
    override fun close() {
        listener?.close()
        listener = null
        connections.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun serve(client: Socket) {
        client.soTimeout = IO_TIMEOUT_MILLIS
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val nonce = DevPush.newNonce()
        output.write("${DevPush.GREETING} $nonce\n".toByteArray())
        output.flush()

        val header = DevPush.readLine(input).split(' ')
        val length = header.getOrNull(0)?.toIntOrNull()
        val mac = header.getOrNull(1)
        val follow = header.getOrNull(2) == DevPush.FOLLOW
        if (header.size != 3 || length == null || mac == null || length !in 1..DevPush.MAX_PACKAGE_BYTES) {
            return reply(client, PushReply(false, "malformed request"))
        }
        val body = ByteArray(length).also(input::readFully)
        val expected = DevPush.mac(token(), nonce, follow, body)
        if (!MessageDigest.isEqual(expected.toByteArray(), mac.lowercase().toByteArray())) {
            // Slows down guessing the token.
            Thread.sleep(REJECT_DELAY_MILLIS)
            return reply(client, PushReply(false, "wrong pairing token"))
        }

        val result = try {
            handler.install(body)
        } catch (e: Exception) {
            PushReply(false, e.message ?: e.javaClass.simpleName)
        }
        reply(client, result)
        val moduleId = result.moduleId
        if (!follow || !result.ok || moduleId == null) return

        // Output goes out as it appears; the computer ends the session by disconnecting.
        client.soTimeout = 0
        val forwarding = scope.launch {
            try {
                handler.output(moduleId).collect { text ->
                    output.write(text.toByteArray())
                    output.flush()
                }
            } catch (e: IOException) {
                runCatching { client.close() }
            }
        }
        try {
            while (input.read() >= 0) Unit
        } catch (e: IOException) {
            // Closed from either side.
        } finally {
            forwarding.cancel()
        }
    }

    private fun reply(client: Socket, reply: PushReply) {
        val line = (if (reply.ok) "OK " else "ERR ") + DevPush.oneLine(reply.message) + "\n"
        client.getOutputStream().apply {
            write(line.toByteArray())
            flush()
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val MAX_CONNECTIONS = 4
        const val IO_TIMEOUT_MILLIS = 30_000
        const val REJECT_DELAY_MILLIS = 1_000L
    }
}

/** The computer's side of a push. */
object DevPushClient {
    private const val CONNECT_TIMEOUT_MILLIS = 5_000

    // Installing stops the module first, which may wait for its callbacks.
    private const val REPLY_TIMEOUT_MILLIS = 60_000

    /**
     * Sends [packageBytes] to the host at [host]:[port].
     *
     * @param onOutput when given and the push succeeds, receives the module's output; the call
     *   then returns only when the host ends the connection or [onOutput] throws.
     * @param onReply called with the host's answer before output starts.
     * @throws IOException when the host cannot be reached or does not speak the protocol.
     */
    fun push(
        host: String,
        port: Int,
        token: String,
        packageBytes: ByteArray,
        onReply: (PushReply) -> Unit = {},
        onOutput: ((String) -> Unit)? = null,
    ): PushReply {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            socket.soTimeout = REPLY_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val greeting = DevPush.readLine(input).split(' ')
            if (greeting.size != 2 || greeting[0] != DevPush.GREETING) throw IOException("not a ModuForge developer connection")

            val follow = onOutput != null
            val mac = DevPush.mac(token, greeting[1], follow, packageBytes)
            socket.getOutputStream().apply {
                write("${packageBytes.size} $mac ${if (follow) DevPush.FOLLOW else DevPush.ONCE}\n".toByteArray())
                write(packageBytes)
                flush()
            }

            val answer = DevPush.readLine(input)
            val reply = PushReply(answer.startsWith("OK "), answer.substringAfter(' ', ""))
            onReply(reply)
            if (!reply.ok || onOutput == null) return reply

            socket.soTimeout = 0
            val reader = input.reader()
            val buffer = CharArray(4096)
            try {
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    onOutput(String(buffer, 0, count))
                }
            } catch (e: IOException) {
                // The host stopped or the connection dropped: the session is over.
            }
            return reply
        }
    }
}
