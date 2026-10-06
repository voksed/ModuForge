package dev.moduforge.packer

import dev.moduforge.core.dev.DevPush
import dev.moduforge.core.dev.DevPushServer
import dev.moduforge.core.dev.PushReply
import dev.moduforge.core.pkg.ModulePackageVerifier
import dev.moduforge.core.pkg.PackageCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PushTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val token = DevPush.newToken()
    private val installed = CopyOnWriteArrayList<String>()
    private lateinit var server: DevPushServer
    private lateinit var project: File
    private lateinit var key: File
    private val originalSettings = pushSettingsFile

    @Before
    fun setUp() {
        pushSettingsFile = File(temp.root, "home/push.properties")
        key = File(temp.root, "key.json").also { run(listOf("keygen", it.path)) }
        project = temp.newFolder("bot")
        run(listOf("init", project.path, "--id", "com.example.bot", "--runtime", "lua", "--entry", "main.lua"))
        File(project, "main.lua").writeText("mf.log('hi')")

        server = DevPushServer(
            token = { token },
            handler = object : DevPushServer.Handler {
                override fun install(packageBytes: ByteArray): PushReply {
                    val file = temp.newFile().apply { writeBytes(packageBytes) }
                    return when (val check = ModulePackageVerifier.verify(file)) {
                        is PackageCheck.Valid -> {
                            installed += check.manifest.id
                            PushReply(true, "${check.manifest.name} installed, started", check.manifest.id)
                        }
                        is PackageCheck.Invalid -> PushReply(false, check.reason)
                    }
                }

                // The flow ends, but the connection stays open until the test closes the server.
                override fun output(moduleId: String): Flow<String> = flowOf("hi\nsecond ", "half\n")
            },
        ).apply { start(port = 0) }
    }

    @After
    fun tearDown() {
        server.close()
        pushSettingsFile = originalSettings
    }

    private fun push(vararg extra: String, print: (String) -> Unit = {}): String =
        run(listOf("push", project.path, "--key", key.path, "--host", "127.0.0.1", "--port", server.port.toString()) + extra, print = print)

    @Test
    fun `push sends a signed package and remembers the phone`() {
        assertEquals("bot installed, started", push("--token", token, "--no-follow"))
        assertEquals(listOf("com.example.bot"), installed)
        assertTrue(project.listFiles().orEmpty().none { it.extension == "mfrg" })

        // The second push needs no token.
        assertEquals("bot installed, started", push("--no-follow"))
        assertEquals(2, installed.size)
    }

    @Test
    fun `push prints the module output line by line`() {
        val printed = CopyOnWriteArrayList<String>()
        push("--token", token) { line ->
            printed += line
            // Stands for Ctrl+C: the last expected line ends the session.
            if (line == "second half") server.close()
        }
        assertTrue(printed.toString(), printed.first().startsWith("bot installed, started"))
        assertEquals(listOf("hi", "second half"), printed.drop(1))
    }

    @Test
    fun `a wrong token is reported and not remembered`() {
        val error = assertThrows(UsageError::class.java) { push("--token", DevPush.newToken(), "--no-follow") }
        assertTrue(error.message, "wrong pairing token" in error.message.orEmpty())
        assertFalse(pushSettingsFile.exists())
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `push explains what is missing`() {
        assertTrue("developer mode" in assertThrows(UsageError::class.java) { push("--no-follow") }.message.orEmpty())

        val port = server.port.toString()
        server.close()
        val unreachable = assertThrows(UsageError::class.java) {
            run(listOf("push", project.path, "--key", key.path, "--host", "127.0.0.1", "--port", port, "--token", token, "--no-follow"))
        }
        assertTrue(unreachable.message, "cannot reach the app" in unreachable.message.orEmpty())
    }

    @Test
    fun `over USB the port is forwarded first and a failure is explained`() {
        val forwarded = mutableListOf<Int>()
        val options = Options(listOf("--key", key.path, "--token", token, "--port", server.port.toString(), "--no-follow"))
        assertEquals("bot installed, started", push(project, options, print = {}, forwardUsb = { forwarded += it; null }))
        assertEquals(listOf(server.port), forwarded)

        val error = assertThrows(UsageError::class.java) { push(project, options, print = {}, forwardUsb = { "adb: no device" }) }
        assertTrue(error.message, "adb: no device" in error.message.orEmpty() && "--host" in error.message.orEmpty())
    }
}
