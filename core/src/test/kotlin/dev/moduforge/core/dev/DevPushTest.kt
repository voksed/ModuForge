package dev.moduforge.core.dev

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DevPushTest {

    private val token = DevPush.newToken()
    private val received = CopyOnWriteArrayList<String>()
    private val output = Channel<String>(Channel.UNLIMITED)

    private val server = DevPushServer(
        token = { token },
        handler = object : DevPushServer.Handler {
            override fun install(packageBytes: ByteArray): PushReply {
                val text = packageBytes.decodeToString()
                received += text
                return if (text.startsWith("bad")) PushReply(false, "invalid package:\n  no manifest") else PushReply(true, "installed", "com.example.m")
            }

            override fun output(moduleId: String): Flow<String> = output.receiveAsFlow()
        },
    ).apply { start(port = 0) }

    @After
    fun stop() = server.close()

    @Test
    fun `token is grouped and typed forms of it are equal`() {
        assertTrue(token, Regex("""[A-Z2-9]{4}(-[A-Z2-9]{4}){3}""").matches(token))
        assertEquals(DevPush.normalizeToken(token), DevPush.normalizeToken(token.lowercase().replace("-", " ")))
        assertNotEquals(token, DevPush.newToken())
    }

    @Test
    fun `a package pushed with the right token is installed`() {
        val reply = DevPushClient.push("127.0.0.1", server.port, token.lowercase(), "package".toByteArray())
        assertEquals(PushReply(true, "installed"), reply)
        assertEquals(listOf("package"), received)
    }

    @Test
    fun `a wrong token installs nothing`() {
        val reply = DevPushClient.push("127.0.0.1", server.port, DevPush.newToken(), "package".toByteArray())
        assertEquals(PushReply(false, "wrong pairing token"), reply)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a rejection arrives as one line`() {
        val reply = DevPushClient.push("127.0.0.1", server.port, token, "bad".toByteArray())
        assertEquals(PushReply(false, "invalid package:; no manifest"), reply)
    }

    @Test
    fun `a recorded request cannot be replayed`() {
        // The MAC of an earlier exchange is bound to that exchange's nonce.
        val body = "package".toByteArray()
        val stale = DevPush.mac(token, DevPush.newNonce(), follow = false, body = body)
        Socket("127.0.0.1", server.port).use { socket ->
            DevPush.readLine(socket.getInputStream())
            socket.getOutputStream().apply {
                write("${body.size} $stale once\n".toByteArray())
                write(body)
                flush()
            }
            assertEquals("ERR wrong pairing token", DevPush.readLine(socket.getInputStream()))
        }
        assertTrue(received.isEmpty())
    }

    @Test
    fun `malformed and oversized requests are refused`() {
        for (header in listOf("x y z", "0 00 once", "${DevPush.MAX_PACKAGE_BYTES + 1} 00 once", "5 00")) {
            Socket("127.0.0.1", server.port).use { socket ->
                DevPush.readLine(socket.getInputStream())
                socket.getOutputStream().apply {
                    write("$header\n".toByteArray())
                    flush()
                }
                assertEquals(header, "ERR malformed request", DevPush.readLine(socket.getInputStream()))
            }
        }
        assertTrue(received.isEmpty())
    }

    @Test
    fun `output follows a successful push until the host stops`() {
        val lines = CopyOnWriteArrayList<String>()
        val replied = CountDownLatch(1)
        val gotOutput = CountDownLatch(1)
        val client = thread {
            DevPushClient.push(
                "127.0.0.1", server.port, token, "package".toByteArray(),
                onReply = { replied.countDown() },
                onOutput = {
                    lines += it
                    if (lines.joinToString("").endsWith("second\n")) gotOutput.countDown()
                },
            )
        }
        assertTrue(replied.await(5, TimeUnit.SECONDS))
        output.trySend("first\n")
        output.trySend("second\n")
        assertTrue(gotOutput.await(5, TimeUnit.SECONDS))
        assertEquals("first\nsecond\n", lines.joinToString(""))

        server.close()
        client.join(5_000)
        assertFalse(client.isAlive)
    }

    @Test
    fun `a failed push is not followed`() {
        val reply = DevPushClient.push("127.0.0.1", server.port, token, "bad".toByteArray(), onOutput = { error("no output expected") })
        assertFalse(reply.ok)
    }
}
