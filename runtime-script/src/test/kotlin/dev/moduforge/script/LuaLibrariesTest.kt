package dev.moduforge.script

import dev.moduforge.sdk.ModuleRuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the libraries shipped with the Lua runtime against an in-memory host. */
class LuaLibrariesTest {

    private fun run(script: String, vararg answers: String?, files: Map<String, String> = emptyMap()): FakeHost {
        val host = FakeHost(answers.toMutableList())
        files.forEach { (path, content) -> host.files[path] = content.toByteArray() }
        return runScript(ModuleRuntimeKind.LUA, script, host)
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
        val host = runScript(
            ModuleRuntimeKind.LUA,
            """mf.log(require("mf.config").marker)""",
            extraFiles = mapOf("mf/config.lua" to """return { marker = "own copy" }"""),
        )
        assertEquals("own copy", host.lines.first())
    }
}
