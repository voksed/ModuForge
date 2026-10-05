package dev.moduforge.sandbox

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityGateway
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.Connection
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the libraries shipped with the Lua runtime against an in-memory host. */
class LuaLibrariesTest {

    private class FakeHost(private val answers: MutableList<String?>) : ModuleContext {
        val lines = CopyOnWriteArrayList<String>()
        val files = mutableMapOf<String, ByteArray>()
        val questions = CopyOnWriteArrayList<String>()
        val finished = CountDownLatch(1)

        override val manifest = ModuleManifest("com.example.t", "T", "1.0.0", "1.0.0", "main.lua", ModuleRuntimeKind.LUA)
        override val capabilities = object : CapabilityGateway {
            override suspend fun request(request: CapabilityRequest) = CapabilityResult.Granted
            override suspend fun isGranted(capability: Capability, target: String?) = true
        }
        override val log = object : ModuleLogger {
            override fun info(message: String) { lines += message }
            override fun warn(message: String) { lines += message }
            override fun error(message: String, cause: Throwable?) { lines += "ERROR $message" }
        }
        override val ui = object : UiSurface {
            override fun show(root: UiNode) = Unit
            override fun clear() = Unit
        }
        override val network = object : NetworkGateway {
            override suspend fun connect(host: String, port: Int, tls: Boolean): Connection = throw IOException("offline")
        }
        override val storage = object : StorageGateway {
            override suspend fun read(path: String) = files[path]
            override suspend fun write(path: String, data: ByteArray) { files[path] = data }
            override suspend fun delete(path: String) = files.remove(path) != null
            override suspend fun list() = files.keys.sorted()
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
    }

    private fun run(script: String, vararg answers: String?, files: Map<String, String> = emptyMap()): FakeHost {
        val host = FakeHost(answers.toMutableList())
        files.forEach { (path, content) -> host.files[path] = content.toByteArray() }
        val module = LuaScriptModule(mapOf("main.lua" to script.trimIndent().toByteArray()), "main.lua")
        runBlocking { module.onStart(host) }
        assertTrue("script did not finish: ${host.lines}", host.finished.await(20, TimeUnit.SECONDS))
        return host
    }

    @Test
    fun `config asks once, trims the answer and remembers it`() {
        val host = run(
            """
            local config = require("mf.config")
            mf.log("first=" .. config.get("token", { ask = "Token?", secret = true }))
            mf.log("second=" .. config.get("token", { ask = "Token?", secret = true }))
            mf.log("default=" .. tostring(config.get("limit", { default = 5 })))
            config.set("count", 3)
            config.forget("limit")
            mf.log("gone=" .. tostring(config.get("limit")))
            """,
            "  secret-1 \n",
        )
        assertEquals(listOf("first=secret-1", "second=secret-1", "default=5", "gone=nil", "script finished", "STOP script finished"), host.lines)
        assertEquals(listOf("Token?|true"), host.questions)
        val stored = host.files.getValue("config.json").decodeToString()
        assertTrue(stored, "\"token\":\"secret-1\"" in stored && "\"count\":3" in stored && "limit" !in stored)
    }

    @Test
    fun `config survives a restart through storage`() {
        val host = run(
            """mf.log("token=" .. require("mf.config").get("token", { ask = "Token?" }))""",
            files = mapOf("config.json" to """{"token":"kept"}"""),
        )
        assertEquals("token=kept", host.lines.first())
        assertTrue(host.questions.isEmpty())
    }

    @Test
    fun `schedule runs due tasks, keeps going after a failure and reports the next deadline`() {
        val host = run(
            """
            local schedule = require("mf.schedule")
            local now = 1000
            mf.time = function() return now end
            local runs = {}
            schedule.every(10, function() runs[#runs + 1] = "a" end)
            schedule.every(30, function() runs[#runs + 1] = "b" end, { immediately = false })
            schedule.every(5, function() error("boom") end)
            mf.log("wait=" .. schedule.step() .. " runs=" .. table.concat(runs))
            now = 1010
            mf.log("wait=" .. schedule.step() .. " runs=" .. table.concat(runs))
            now = 1030
            mf.log("wait=" .. schedule.step() .. " runs=" .. table.concat(runs))
            """,
        )
        val steps = host.lines.filter { it.startsWith("wait=") }
        assertEquals(listOf("wait=5 runs=a", "wait=5 runs=aa", "wait=5 runs=aaab"), steps)
        assertEquals(3, host.lines.count { "scheduled task failed" in it && "boom" in it })
    }

    private val telegramStub = """
        local sent = {}
        local updates = {
            { update_id = 7, message = { chat = { id = 5000000000 }, from = { first_name = "Ada" }, text = "/start now" } },
            { update_id = 8, message = { chat = { id = 5000000000 }, text = "/crash" } },
            { update_id = 9, message = { chat = { id = 5000000000 }, text = "plain words" } },
            { update_id = 10, callback_query = { id = "q", data = "yes" } },
        }
        mf.http = function(request)
            local method = request.url:match("/([%a]+)${'$'}")
            local body = mf.json.decode(request.body)
            if not request.url:find("bot123:abc/", 1, true) then
                return { status = 401, body = '{"ok":false,"description":"Unauthorized"}' }
            elseif method == "getMe" then
                return { status = 200, body = '{"ok":true,"result":{"username":"test_bot"}}' }
            elseif method == "getUpdates" then
                mf.log("poll offset=" .. tostring(body.offset))
                local batch = updates
                updates = {}
                return { status = 200, body = mf.json.encode({ ok = true, result = batch }) }
            elseif method == "sendMessage" then
                mf.log("send " .. tostring(body.chat_id) .. ": " .. body.text)
                return { status = 200, body = '{"ok":true,"result":{}}' }
            end
        end
    """.trimIndent()

    @Test
    fun `telegram bot routes commands, text and callbacks and persists its offset`() {
        val host = run(
            telegramStub + "\n" + """
            local telegram = require("mf.telegram")
            local bot = assert(telegram.bot())
            bot:command("/start", function(message, arguments) bot:reply(message, "hi " .. message.from.first_name .. " [" .. arguments .. "]") end)
            bot:command("crash", function() error("handler bug") end)
            bot:on("text", function(message) bot:reply(message, "echo " .. message.text) end)
            bot:on("callback", function(query) mf.log("callback " .. query.data) end)
            mf.log("handled=" .. tostring(bot:poll()))
            mf.log("handled=" .. tostring(bot:poll()))
            mf.log("keyboard=" .. mf.json.encode(telegram.keyboard({ { { "Yes", "y" } } })))
            """.trimIndent(),
            "123:abc",
        )
        assertEquals(
            listOf(
                "poll offset=0",
                "send 5000000000: hi Ada [now]",
                "send 5000000000: echo plain words",
                "callback yes",
                "handled=4",
                "poll offset=11",
                "handled=0",
            ),
            host.lines.filter { !it.startsWith("handler failed") && !it.startsWith("keyboard") && "script finished" !in it },
        )
        assertTrue(host.lines.any { it.startsWith("handler failed") && "handler bug" in it })
        assertTrue(host.lines.any { it == """keyboard={"inline_keyboard":[[{"text":"Yes","callback_data":"y"}]]}""" || it == """keyboard={"inline_keyboard":[[{"callback_data":"y","text":"Yes"}]]}""" })
        assertEquals(listOf("Bot token from @BotFather|true"), host.questions)
        val stored = host.files.getValue("config.json").decodeToString()
        assertTrue(stored, "\"telegram_offset\":11" in stored && "\"telegram_token\":\"123:abc\"" in stored)
    }

    @Test
    fun `telegram bot forgets a token Telegram rejects`() {
        val host = run(
            telegramStub + "\n" + """
            local bot = assert(require("mf.telegram").bot())
            local ok, err = bot:run()
            mf.log("run=" .. tostring(ok) .. "," .. tostring(err))
            """.trimIndent(),
            "wrong-token",
        )
        assertTrue(host.lines.toString(), "run=nil,Unauthorized" in host.lines)
        assertTrue("token" !in host.files.getValue("config.json").decodeToString())
    }

    @Test
    fun `module files take precedence over the bundled libraries`() {
        val host = FakeHost(mutableListOf())
        val module = LuaScriptModule(
            mapOf(
                "main.lua" to """mf.log(require("mf.config").marker)""".toByteArray(),
                "mf/config.lua" to """return { marker = "own copy" }""".toByteArray(),
            ),
            "main.lua",
        )
        runBlocking { module.onStart(host) }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        assertEquals("own copy", host.lines.first())
    }
}
