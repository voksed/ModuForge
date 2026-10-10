package dev.moduforge.script

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleRuntimeKind.JS
import dev.moduforge.sdk.ModuleRuntimeKind.LUA
import dev.moduforge.sdk.ui.TextStyle
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** The host API as seen from both script languages, against an in-memory host. */
class ScriptApiTest {

    // 1971-01-01 01:01:01 UTC, a Friday.
    private val moment = 365 * 86400 + 3661

    @Test
    fun `dates are formatted and broken into fields`() {
        val lua = runScript(
            LUA,
            """
            mf.log(mf.date("!%Y-%m-%d %H:%M:%S day %j %%", $moment))
            local t = mf.date("!*t", $moment)
            mf.log(t.year .. "/" .. t.month .. "/" .. t.day .. " " .. t.hour .. ":" .. t.min .. ":" .. t.sec .. " wday " .. t.wday .. " yday " .. t.yday)
            mf.log(tostring(#mf.date() == 19) .. " " .. tostring(os.time() > 1700000000) .. " " .. os.date("!%y", $moment))
            """,
        )
        assertEquals(listOf("1971-01-01 01:01:01 day 001 %", "1971/1/1 1:1:1 wday 6 yday 1", "true true 71"), lua.output)

        val js = runScript(
            JS,
            """
            mf.log(mf.date("!%Y-%m-%d %H:%M:%S day %j %%", $moment));
            var t = mf.dateFields($moment, true);
            mf.log(t.year + "/" + t.month + "/" + t.day + " " + t.hour + ":" + t.min + ":" + t.sec + " wday " + t.wday + " yday " + t.yday);
            mf.log((mf.date().length === 19) + " " + (mf.time() > 1700000000));
            """,
        )
        assertEquals(listOf("1971-01-01 01:01:01 day 001 %", "1971/1/1 1:1:1 wday 6 yday 1", "true true"), js.output)
    }

    private val digests = listOf(
        "900150983cd24fb0d6963f7d28e17f72",
        "a9993e364706816aba3e25717850c26c9cd0d89d",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        "aGVsbG8=", "hello", "-_8", "68656c6c6f", "hello", "32 16",
    )

    @Test
    fun `hashes, HMAC and encodings match known values in Lua`() {
        val host = runScript(
            LUA,
            """
            mf.log(mf.hash.md5("abc"))
            mf.log(mf.hash.sha1("abc"))
            mf.log(mf.hash.sha256("abc"))
            mf.log(mf.hmac.sha256("key", "The quick brown fox jumps over the lazy dog"))
            mf.log(mf.base64.encode("hello"))
            mf.log(mf.base64.decode("aGVsbG8"))
            mf.log(mf.base64.encode("\251\255", true))
            mf.log(mf.hex.encode("hello"))
            mf.log(mf.hex.decode("68656c6c6f"))
            mf.log(#mf.hash.sha256("abc", true) .. " " .. #mf.random(16))
            mf.log(tostring(mf.base64.decode("***")) .. " " .. tostring(select(2, mf.hex.decode("zz"))))
            mf.log(tostring(pcall(mf.hash.sha256)))
            """,
        )
        assertEquals(digests + listOf("nil not hexadecimal text", "false"), host.output.map { if (it.startsWith("false")) "false" else it })
    }

    @Test
    fun `hashes, HMAC and encodings match known values in JavaScript`() {
        val host = runScript(
            JS,
            """
            mf.log(mf.hash.md5("abc"));
            mf.log(mf.hash.sha1("abc"));
            mf.log(mf.hash.sha256("abc"));
            mf.log(mf.hmac.sha256("key", "The quick brown fox jumps over the lazy dog"));
            mf.log(mf.base64.encode("hello"));
            mf.log(mf.base64.decode("aGVsbG8"));
            mf.log(mf.base64.encode(new Uint8Array([251, 255]), true));
            mf.log(mf.hex.encode("hello"));
            mf.log(mf.base64.decode(mf.base64.encode(mf.hex.decode("68656c6c6f"))));
            mf.log(mf.hash.sha256("abc", true).length + " " + mf.random(16).length);
            try { mf.hex.decode("zz"); } catch (e) { mf.log(e.name + ": " + e.message); }
            try { mf.hash.sha256(42); } catch (e) { mf.log(e.name); }
            """,
        )
        assertEquals(digests + listOf("Error: not hexadecimal text", "TypeError"), host.output)
    }

    /** Serves `/start` (redirect), `/moved` (303 after POST), `/final`, `/upload` and `/loop`. */
    private val web: FakeServer = { host, _, _, input, output ->
        val (head, body) = readHttpRequest(input)
        val (method, path) = head.first().split(' ')
        val headers = head.drop(1).joinToString("|")
        when (path) {
            "/start" -> output.respond("302 Found", "", "Location: /final?from=start")
            "/moved" -> output.respond("303 See Other", "", "Location: https://other.example/final")
            "/loop" -> output.respond("301 Moved", "", "Location: /loop")
            "/upload" -> output.respond("200 OK", "$method ${headers.contains("multipart/form-data; boundary=")} " + body.decodeToString().replace("\r\n", "~"))
            "/form" -> output.respond("200 OK", "$method ${headers.contains("application/x-www-form-urlencoded")} ${body.decodeToString()}")
            else -> output.respond("200 OK", "$method $host$path auth=${headers.contains("Authorization")} body=${body.size}", "X-Seen: yes")
        }
    }

    @Test
    fun `http follows redirects, drops credentials across hosts and reports loops in Lua`() {
        val host = runScript(
            LUA,
            """
            local r = mf.http{url = "http://site.example/start"}
            mf.log(r.status .. " " .. r.body .. " " .. r.url .. " " .. r.headers["x-seen"])
            r = mf.http{url = "http://site.example/start", redirects = 0}
            mf.log(r.status .. " " .. r.headers.location)
            r = mf.http{url = "http://site.example/moved", method = "POST", body = "data", headers = {Authorization = "secret"}}
            mf.log(r.body .. " " .. r.url)
            mf.log(tostring(select(2, mf.http{url = "http://site.example/loop"})))
            """,
            FakeHost(server = web),
        )
        assertEquals(
            listOf(
                "200 GET site.example/final?from=start auth=false body=0 http://site.example/final?from=start yes",
                "302 /final?from=start",
                "GET other.example/final auth=false body=0 https://other.example/final",
                "too many redirects",
            ),
            host.output,
        )
        assertTrue(host.connections.toString(), "other.example:443:true" in host.connections)
    }

    @Test
    fun `http sends forms and files`() {
        val lua = runScript(
            LUA,
            """
            mf.log(mf.http{url = "http://s.example/form", form = {a = "1 2"}}.body)
            local r = mf.http{url = "http://s.example/upload", form = {chat = "7"}, files = {photo = {filename = "a.txt", type = "text/plain", content = "DATA"}}}
            mf.log(r.body)
            """,
            FakeHost(server = web),
        )
        assertEquals("POST true a=1%202", lua.output[0])
        val upload = lua.output[1].replace(Regex("mf[0-9a-f]{24}"), "B")
        assertEquals(
            "POST true --B~Content-Disposition: form-data; name=\"chat\"~~7~" +
                "--B~Content-Disposition: form-data; name=\"photo\"; filename=\"a.txt\"~Content-Type: text/plain~~DATA~--B--~",
            upload,
        )

        val js = runScript(
            JS,
            """
            var r = mf.http({url: "http://s.example/upload", files: [{field: "doc", filename: "b.bin", content: new Uint8Array([72, 73])}]});
            mf.log(r.status + " " + r.body.indexOf('name="doc"; filename="b.bin"') + " " + (r.body.indexOf("~HI~") > 0));
            var page = mf.http({url: "http://s.example/start"});
            mf.log(page.url + " " + page.headers["x-seen"] + " " + page.bytes().length);
            try { mf.http({url: "http://s.example/loop"}); } catch (e) { mf.log(e.message); }
            """,
            FakeHost(server = web),
        )
        assertTrue(js.output[0], Regex("""200 [1-9]\d* true""").matches(js.output[0]))
        assertEquals("http://s.example/final?from=start yes ${"GET s.example/final?from=start auth=false body=0".length}", js.output[1])
        assertEquals("too many redirects", js.output[2])
    }

    /** Line-based echo server that greets, answers each line and closes on `bye`. */
    private val echo: FakeServer = { _, _, _, input, output ->
        output.write("hello\r\n".toByteArray())
        output.flush()
        val reader = input.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == "bye") break
            output.write("echo:$line\n".toByteArray())
            output.flush()
        }
    }

    @Test
    fun `raw connections read lines with timeouts and see the close`() {
        val lua = runScript(
            LUA,
            """
            local s = assert(mf.connect("irc.example", 6667))
            mf.log(s:read_line())
            mf.log(tostring(select(2, s:read_line(0.2))))
            s:write("one\n")
            mf.log(s:read_line(5))
            s:write("two\n")
            mf.log(s:read_exactly(4, 5) .. "|" .. s:read(100, 5))
            s:write("bye\n")
            mf.log(tostring(select(2, s:read_line(5))))
            s:close()
            mf.log(tostring(select(2, s:write("x"))))
            """,
            FakeHost(server = echo),
        )
        assertEquals(listOf("hello", "timeout", "echo:one", "echo|:two\n", "closed", "connection is closed"), lua.output)

        val js = runScript(
            JS,
            """
            var s = mf.connect("irc.example", 6697, {tls: true});
            mf.log(s.readLine());
            try { s.readLine(0.2); } catch (e) { mf.log(e.message); }
            s.write("one\n");
            mf.log(s.readLine(5));
            s.write("bye\n");
            mf.log(String(s.readLine(5)));
            s.close();
            """,
            FakeHost(server = echo),
        )
        assertEquals(listOf("hello", "timeout", "echo:one", "null"), js.output)
        assertEquals(listOf("irc.example:6697:true"), js.connections.toList())
    }

    /** Minimal WebSocket server: greets, pings, sends a fragmented message, echoes one frame, closes. */
    private val socketServer: FakeServer = { _, _, _, input, output ->
        val (head, _) = readHttpRequest(input)
        val key = head.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        fun send(first: Int, payload: ByteArray) {
            output.write(first)
            output.write(payload.size)
            output.write(payload)
            output.flush()
        }
        send(0x81, "${head.first()} ${head.any { it == "X-Token: t" }}".toByteArray())
        send(0x89, "p".toByteArray())
        send(0x01, "frag".toByteArray())
        send(0x80, "ment".toByteArray())
        val frames = generateSequence { readClientFrame(input) }.take(2).toList()
        check(frames[0].first == 0xA) { "expected a pong first, got ${frames[0].first}" }
        send(0x82, frames[1].second.reversedArray())
        send(0x88, byteArrayOf(3, 0xE8.toByte()))
    }

    private fun readClientFrame(input: InputStream): Pair<Int, ByteArray> {
        val opcode = input.read() and 0x0F
        val second = input.read()
        check(second and 0x80 != 0) { "client frames must be masked" }
        val mask = ByteArray(4).also { input.read(it) }
        val payload = ByteArray(second and 0x7F) { (input.read() xor mask[it % 4].toInt()).toByte() }
        return opcode to payload
    }

    @Test
    fun `websocket handshakes, answers pings, joins fragments and sees the close`() {
        val lua = runScript(
            LUA,
            """
            local ws = assert(mf.websocket("wss://gate.example/chat?v=1", {["X-Token"] = "t"}))
            mf.log((ws:receive(5)))
            local data, text = ws:receive(5)
            mf.log(data .. " " .. tostring(text))
            ws:send_binary("abc")
            data, text = ws:receive(5)
            mf.log(data .. " " .. tostring(text))
            mf.log(tostring(select(2, ws:receive(5))))
            mf.log(tostring(select(2, ws:send("late"))))
            """,
            FakeHost(server = socketServer),
        )
        assertEquals(
            listOf("GET /chat?v=1 HTTP/1.1 true", "fragment true", "cba false", "closed", "connection is closed"),
            lua.output,
        )
        assertEquals(listOf("gate.example:443:true"), lua.connections.toList())

        val js = runScript(
            JS,
            """
            var ws = mf.websocket("ws://gate.example:8080/chat");
            mf.log(ws.receive(5));
            mf.log(ws.receive(5));
            ws.send(new Uint8Array([1, 2, 3]));
            var reply = ws.receive(5);
            mf.log(reply.length + ":" + reply[0] + reply[1] + reply[2]);
            mf.log(String(ws.receive(5)));
            """,
            FakeHost(server = socketServer),
        )
        assertEquals(listOf("GET /chat HTTP/1.1 false", "fragment", "3:321", "null"), js.output)
        assertEquals(listOf("gate.example:8080:false"), js.connections.toList())
    }

    @Test
    fun `websocket refuses a server that does not complete the handshake`() {
        val refusing: FakeServer = { _, _, _, input, output ->
            readHttpRequest(input)
            output.respond("403 Forbidden")
        }
        val host = runScript(LUA, """mf.log(tostring(select(2, mf.websocket("ws://x.example/"))))""", FakeHost(server = refusing))
        assertEquals(listOf("server refused the WebSocket: HTTP/1.1 403 Forbidden"), host.output)
    }

    private val expectedTree = UiNode.Column(
        listOf(
            UiNode.Text("Title", TextStyle.TITLE),
            UiNode.Row(listOf(UiNode.Button("ok", "OK"), UiNode.Button("no", "No", enabled = false))),
            UiNode.TextField("name", "Ada", "Name"),
            UiNode.Text("plain"),
        ),
    )

    @Test
    fun `scripts show an interface and receive its events`() {
        val luaHost = FakeHost()
        val luaModule = ScriptRuntimes.create(
            LUA,
            mapOf(
                "main.lua" to """
                    mf.ui.show({
                        {type = "text", text = "Title", style = "title"},
                        {type = "row", children = {
                            {type = "button", id = "ok", label = "OK"},
                            {type = "button", id = "no", label = "No", enabled = false},
                        }},
                        {type = "field", id = "name", value = "Ada", label = "Name"},
                        "plain",
                    })
                    local event = mf.ui.wait(10)
                    mf.log(event.type .. " " .. event.id)
                    event = mf.ui.wait(10)
                    mf.log(event.type .. " " .. event.id .. " " .. event.value)
                    mf.log(tostring(mf.ui.wait(0.1)))
                    mf.ui.clear()
                    mf.log(tostring(select(2, pcall(mf.ui.show, {{type = "video"}}))))
                """.trimIndent().toByteArray(),
            ),
            "main.lua",
        )
        runBlocking {
            luaModule.onStart(luaHost)
            luaModule.onUiEvent(luaHost, UiEvent.Click("ok"))
            luaModule.onUiEvent(luaHost, UiEvent.TextChanged("name", "Grace"))
        }
        assertTrue(luaHost.finished.await(30, TimeUnit.SECONDS))
        assertEquals(listOf("click ok", "text name Grace", "nil"), luaHost.output.take(3))
        assertTrue(luaHost.output[3], "video" in luaHost.output[3])
        assertEquals(expectedTree, luaHost.shown[0])
        assertNull(luaHost.shown[1])

        val jsHost = FakeHost()
        val jsModule = ScriptRuntimes.create(
            JS,
            mapOf(
                "main.js" to """
                    mf.ui.show([
                        {type: "text", text: "Title", style: "title"},
                        {type: "row", children: [
                            {type: "button", id: "ok", label: "OK"},
                            {type: "button", id: "no", label: "No", enabled: false}
                        ]},
                        {type: "field", id: "name", value: "Ada", label: "Name"},
                        "plain"
                    ]);
                    var event = mf.ui.wait(10);
                    mf.log(event.type + " " + event.id);
                    mf.log(String(mf.ui.wait(0.1)));
                    try { mf.ui.show([{type: "video"}]); } catch (e) { mf.log(e.name); }
                """.trimIndent().toByteArray(),
            ),
            "main.js",
        )
        runBlocking {
            jsModule.onStart(jsHost)
            jsModule.onUiEvent(jsHost, UiEvent.Click("ok"))
        }
        assertTrue(jsHost.finished.await(30, TimeUnit.SECONDS))
        assertEquals(listOf("click ok", "null", "TypeError"), jsHost.output)
        assertEquals(expectedTree, jsHost.shown[0])
    }

    @Test
    fun `JavaScript sees permissions, storage, questions and failures the JavaScript way`() {
        val host = runScript(
            JS,
            """
            mf.log(mf.id + " " + mf.name + " " + mf.version);
            mf.log(mf.request("NETWORK_OUTBOUND", "why") + " " + mf.request("CLIPBOARD", "why") + " " + mf.request("NOPE", "why"));
            mf.log(mf.granted("FILE_SANDBOXED") + " " + mf.granted("CLIPBOARD"));
            mf.storage.write("dir/a.txt", "привет");
            mf.storage.write("b.bin", new Uint8Array([0, 255]));
            mf.log(mf.storage.read("dir/a.txt") + " " + mf.storage.readBytes("b.bin")[1] + " " + mf.storage.read("none") + " " + mf.storage.list().join(","));
            mf.log(mf.storage.delete("b.bin") + " " + mf.storage.delete("b.bin"));
            mf.log(mf.ask("Token?", true) + " " + mf.ask("Again?"));
            mf.notify("Title", "Text");
            console.log("a", 1, {b: 2}.b);
            mf.log(mf.urlencode("a b&c") + " " + typeof java + " " + typeof Packages);
            try { mf.http({url: "http://offline.example/"}); } catch (e) { mf.log(e.name + ": " + e.message); }
            const sum = [1, 2, 3].map(x => x * 2).reduce((a, b) => a + b, 0);
            mf.log(`template ${'$'}{sum}`);
            """,
            FakeHost(mutableListOf("secret", null), declared = setOf(Capability.NETWORK_OUTBOUND, Capability.FILE_SANDBOXED)),
        )
        assertEquals(
            listOf(
                "com.example.t T 1.0.0",
                "true false false",
                "true false",
                "привет 255 null b.bin,dir/a.txt",
                "true false",
                "secret null",
                "NOTIFY Title|Text",
                "a 1 2",
                "a%20b%26c undefined undefined",
                "Error: offline",
                "template 12",
            ),
            host.output,
        )
        assertEquals(listOf("Token?|true", "Again?|false"), host.questions.toList())
    }

    @Test
    fun `a JavaScript error stops the module with the place it happened`() {
        val host = runScript(JS, "mf.log('before');\nnoSuchFunction();\nmf.log('after');")
        assertEquals("before", host.lines[0])
        assertTrue(host.lines[1], host.lines[1].startsWith("ERROR script failed:") && "noSuchFunction" in host.lines[1] && "main.js:2" in host.lines[1])
        assertTrue(host.lines.last().startsWith("STOP script failed:"))
    }

    @Test
    fun `device services are called by position or by name and answer with plain values in Lua`() {
        val host = runScript(
            LUA,
            """
            local apps = mf.apps.list()
            mf.log("app=" .. apps[1].package .. " " .. apps[1].name)
            mf.log("info=" .. mf.screen.info().width .. "x" .. mf.screen.info().height)
            mf.screen.tap(100, 200)
            mf.screen.tap{ x = 5, y = 6, ms = 80 }
            mf.screen.swipe(1, 2, 3, 4)
            mf.camera.photo{ path = "a.jpg", lens = "front" }
            mf.apps.launch("Example")
            local ok, err = mf.screen.back()
            mf.log("back=" .. tostring(ok) .. " " .. tostring(err))
            """,
        )
        assertEquals(
            listOf("app=com.example.app Example", "info=1080x2400", "back=nil accessibility service is off"),
            host.output.take(3),
        )
        assertEquals(
            listOf(
                "apps.list {}", "screen.info {}", "screen.info {}",
                """screen.tap {"x":100.0,"y":200.0}""", """screen.tap {"ms":80.0,"x":5.0,"y":6.0}""",
                """screen.swipe {"x1":1.0,"x2":3.0,"y1":2.0,"y2":4.0}""",
                """camera.photo {"lens":"front","path":"a.jpg"}""", """apps.launch {"app":"Example"}""", "screen.back {}",
            ),
            host.deviceCalls.toList(),
        )
    }

    @Test
    fun `device services are called by position or by name and fail with an Error in JavaScript`() {
        val host = runScript(
            JS,
            """
            var apps = mf.apps.list();
            mf.log("app=" + apps[0].package + " " + apps[0].name);
            var info = mf.screen.info();
            mf.log("info=" + info.width + "x" + info.height);
            mf.screen.tap(100, 200);
            mf.screen.tap({ x: 5, y: 6, ms: 80 });
            mf.camera.photo({ path: "a.jpg" });
            mf.apps.open("https://example.org");
            try { mf.screen.back(); } catch (e) { mf.log("back: " + e.message); }
            """,
            FakeHost(),
        )
        assertEquals(listOf("app=com.example.app Example", "info=1080x2400", "back: accessibility service is off"), host.output)
        assertEquals(
            listOf(
                "apps.list {}", "screen.info {}", """screen.tap {"x":100.0,"y":200.0}""", """screen.tap {"ms":80.0,"x":5.0,"y":6.0}""",
                """camera.photo {"path":"a.jpg"}""", """apps.open {"url":"https://example.org"}""", "screen.back {}",
            ),
            host.deviceCalls.toList(),
        )
    }

    @Test
    fun `JavaScript modules load package files and bundled libraries`() {
        val host = runScript(
            JS,
            """
            var util = require("./lib/util");
            var again = require("lib/util.js");
            mf.log(util.double(21) + " " + (util === again) + " " + util.sibling());
            var config = require("mf/config");
            mf.log(config.get("city", {ask: "City?"}) + " " + config.get("city") + " " + config.get("limit", {"default": 5}));
            config.forget("limit");
            mf.log(String(config.get("limit")));
            try { require("missing"); } catch (e) { mf.log(e.message); }
            """,
            FakeHost(mutableListOf("  Baku ")),
            extraFiles = mapOf(
                "lib/util.js" to "exports.double = function (n) { return n * 2; };\nexports.sibling = function () { return require('./other').name; };",
                "lib/other.js" to "module.exports = { name: 'other' };",
            ),
        )
        assertEquals(listOf("42 true other", "Baku Baku 5", "null", "module 'missing' not found"), host.output)
        val stored = host.files.getValue("config.json").decodeToString()
        assertTrue(stored, "\"city\":\"Baku\"" in stored && "limit" !in stored)
        assertEquals("""{"city":{"label":"City?","secret":false}}""", host.files.getValue("config.meta.json").decodeToString())
    }

    @Test
    fun `a javascript syntax error names the unsupported feature`() {
        val result = runScript(dev.moduforge.sdk.ModuleRuntimeKind.JS, "class A {}")
        assertTrue(result.output.toString(), result.output.any { "class is not supported" in it })
    }
}
