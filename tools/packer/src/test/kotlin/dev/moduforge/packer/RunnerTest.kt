package dev.moduforge.packer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** `mfrg run`: modules executed on the desktop with the real script runtime. */
class RunnerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun project(runtime: String, entry: String, permissions: String, script: String): File {
        val dir = temp.newFolder()
        File(dir, "moduforge.json").writeText(
            """{"id":"com.example.run","name":"Run","version":"1.0.0","sdkRange":">=1.0.0 <2.0.0",
               "runtime":"$runtime","entry":"$entry","permissions":[$permissions]}""",
        )
        File(dir, entry).writeText(script.trimIndent())
        return dir
    }

    /** Runs the module and returns what it printed, without the two header lines. */
    private fun execute(vararg args: String, answers: List<String> = emptyList()): List<String> {
        val lines = mutableListOf<String>()
        val input = answers.toMutableList()
        run(listOf("run") + args, readLine = { input.removeFirstOrNull() }, print = { synchronized(lines) { lines += it } })
        return lines.drop(2)
    }

    @Test
    fun `a pretend phone answers device calls so that a scenario can be tried on a computer`() {
        val dir = project(
            "js", "main.js", "\"LAUNCH_APPS\", \"SCREEN_CONTROL\", \"CAMERA\", \"FILE_SANDBOXED\"",
            """
            var apps = mf.apps.list();
            mf.log(apps.length + " apps");
            mf.apps.launch("Calculator");
            mf.screen.click("7");
            mf.screen.tap(100, 200);
            var photo = mf.camera.photo("shot.jpg");
            mf.log("photo " + photo.width + "x" + photo.height + " " + (mf.storage.readBytes("shot.jpg").length > 100));
            try { mf.apps.launch("Nope"); } catch (e) { mf.log(e.message); }
            """,
        )
        assertEquals(
            listOf(
                "3 apps", "[apps] launch Calculator", "[screen] click \"7\"", "[screen] tap 100.0,200.0",
                "[camera] photo with the back camera saved as shot.jpg (a grey picture)", "photo 640x480 true",
                "no app is called \"Nope\"",
            ),
            execute(dir.path).filter { !it.startsWith("script finished") },
        )
    }

    @Test
    fun `device calls without their permission are refused on the computer too`() {
        val dir = project("lua", "main.lua", "\"FILE_SANDBOXED\"", "local r, e = mf.screen.tap(1, 2); mf.log(tostring(r) .. ' ' .. tostring(e))")
        assertEquals("nil SCREEN_CONTROL is not granted", execute(dir.path).first())
    }

    @Test
    fun `a Python module runs on the computer with its project files and reports errors with the line`() {
        val dir = project(
            "python", "main.py", "\"FILE_SANDBOXED\", \"NOTIFICATIONS\"",
            """
            import mf
            from lib import util
            from mf import config
            name = config.get("name", ask="Name?")
            mf.log("hello " + name + " " + str(util.double(21)))
            mf.notify("done")
            """,
        )
        File(dir, "lib").mkdirs()
        File(dir, "lib/__init__.py").writeText("")
        File(dir, "lib/util.py").writeText("def double(n):\n    return n * 2\n")
        assertEquals(listOf("[question] Name?", "hello Ann 42", "[notification] done"), execute(dir.path, answers = listOf("Ann")).filter { !it.startsWith("script finished") })

        val failing = temp.newFile("bad.py").apply { writeText("print('a')\n\nprint(1 / 0)\n") }
        val error = assertThrows(ScriptFailed::class.java) { execute(failing.path) }
        assertTrue(error.message, "ZeroDivisionError: division by zero (bad.py:3)" in error.message.orEmpty() || "main.py:3" in error.message.orEmpty())
    }

    @Test
    fun `a single script finds the scripts next to it`() {
        val dir = temp.newFolder()
        File(dir, "lib").mkdirs()
        File(dir, "lib/text.js").writeText("exports.shout = function (s) { return s.toUpperCase() + '!'; };")
        File(dir, "lib/text.lua").writeText("return { shout = function(s) return s:upper() .. '!' end }")
        File(dir, "bot.js").writeText("mf.log(require('./lib/text').shout('js'));")
        File(dir, "bot.lua").writeText("mf.log(require('lib.text').shout('lua'))")

        assertEquals("JS!", execute(File(dir, "bot.js").path).first())
        assertEquals("LUA!", execute(File(dir, "bot.lua").path).first())
    }

    @Test
    fun `a Lua project runs with storage that survives between runs and questions from the console`() {
        val dir = project(
            "lua", "main.lua", "\"FILE_SANDBOXED\", \"NOTIFICATIONS\"",
            """
            local util = require("lib.util")
            local config = require("mf.config")
            local runs = (tonumber(mf.storage.read("runs.txt")) or 0) + 1
            mf.storage.write("runs.txt", tostring(runs))
            mf.log("run " .. runs .. " for " .. config.get("who", { ask = "Who?" }) .. " " .. util.mark)
            mf.notify("Done", "ok")
            mf.ui.show({ {type = "text", text = "Hi"}, {type = "button", id = "go", label = "Go"} })
            """,
        )
        File(dir, "lib").mkdirs()
        File(dir, "lib/util.lua").writeText("return { mark = 'lib' }")

        assertEquals(
            listOf("[question] Who?", "run 1 for Ada lib", "[notification] Done: ok", "[interface]", "  Hi", "  [ Go ] id=go", "script finished"),
            execute(dir.path, answers = listOf("Ada")),
        )
        assertEquals("run 2 for Ada lib", execute(dir.path)[0])
        assertTrue(File(dir, ".mfrg-run/storage/com.example.run/runs.txt").isFile)

        val key = File(temp.root, "key.json").also { run(listOf("keygen", it.path)) }
        val out = File(temp.root, "run.mfrg")
        run(listOf("pack", dir.path, "--key", key.path, "--out", out.path))
        ZipFile(out).use { zip -> assertFalse(zip.entries().asSequence().any { "mfrg-run" in it.name || "runs.txt" in it.name }) }
    }

    @Test
    fun `a single JavaScript file runs without a manifest`() {
        val script = File(temp.root, "price-check.js").apply {
            writeText("mf.storage.write('a', 'b');\nmf.log(mf.name + ' ' + mf.id + ' ' + mf.storage.read('a') + ' ' + mf.hash.md5('abc'));")
        }
        assertEquals(listOf("price-check local.pricecheck b 900150983cd24fb0d6963f7d28e17f72", "script finished"), execute(script.path))
    }

    @Test
    fun `undeclared and denied permissions behave as on a device`() {
        val dir = project(
            "lua", "main.lua", "\"FILE_SANDBOXED\"",
            """
            mf.log(tostring(select(2, mf.http{url = "https://example.com/"})))
            mf.log(tostring(select(2, mf.request("NETWORK_OUTBOUND", "why"))))
            mf.log(tostring(select(2, mf.request("FILE_SANDBOXED", "why"))) .. " " .. tostring(select(2, mf.storage.read("a"))))
            """,
        )
        assertEquals(
            listOf("NETWORK_OUTBOUND is not granted", "NOT_DECLARED", "USER_DENIED FILE_SANDBOXED is not granted", "script finished"),
            execute(dir.path, "--deny", "FILE_SANDBOXED"),
        )
        assertThrows(UsageError::class.java) { execute(dir.path, "--deny", "FLYING") }
    }

    @Test
    fun `a failing script is reported as an error with its place`() {
        val dir = project("js", "main.js", "", "mf.log('start');\nboom();")
        val shown = mutableListOf<String>()
        val error = assertThrows(ScriptFailed::class.java) {
            run(listOf("run", dir.path), print = { shown += it })
        }
        assertTrue(error.message, "script failed" in error.message!! && "main.js:2" in error.message!!)
        // The error is printed once, while the module runs; the caller only sets the exit code.
        assertEquals(1, shown.count { "script failed" in it && "main.js:2" in it })
        assertThrows(UsageError::class.java) { execute(File(temp.root, "nowhere").path) }
        assertThrows(UsageError::class.java) { execute(temp.newFile("notes.txt").path) }
    }

    @Test
    fun `a single script gets only the permissions its code uses`() {
        val plain = File(temp.newFolder(), "plain.py").apply { writeText("print('hi')\n") }
        val shown = mutableListOf<String>()
        run(listOf("run", plain.path), print = { shown += it })
        assertTrue(shown.toString(), shown.any { it == "permissions: none" })

        val fetcher = File(temp.newFolder(), "fetch.py").apply { writeText("import mf\nmf.log(mf.storage.list())\n") }
        val second = mutableListOf<String>()
        run(listOf("run", fetcher.path), print = { second += it })
        assertTrue(second.toString(), second.any { it == "permissions: FILE_SANDBOXED" })
    }

    @Test
    fun `a single script is packed from its header and its code`() {
        val script = File(temp.newFolder(), "water.py").apply {
            writeText(
                listOf("# @name Water reminder", "# @version 2.1.0", "# @description Drink.", "import mf", "mf.notify('Water', 'now')", "")
                    .joinToString(System.lineSeparator()),
            )
        }
        val out = File(temp.root, "water.mfrg")
        val key = File(temp.root, "key.json")
        run(listOf("keygen", key.path))
        val report = run(listOf("pack", script.path, "--key", key.path, "--out", out.path))
        assertTrue(report, "Water reminder 2.1.0" in report && "NOTIFICATIONS" in report)
        val check = run(listOf("verify", out.path))
        assertTrue(check, "Water reminder" in check && "python" in check.lowercase())
    }

    @Test
    fun `a module is tried against prepared answers and a faster clock`() {
        val dir = temp.newFolder()
        val script = File(dir, "check.py")
        script.writeText(
            listOf(
                "name = input('Name?')",
                "r = mf.http('http://api.test/items?x=1')",
                "mf.log(name, r.status, r.json()['n'])",
                "started = mf.time()",
                "mf.sleep(3600)",
                "mf.log('slept', round(mf.time() - started) >= 3600)",
                "mf.log(mf.http('http://other.test/').status)",
            ).joinToString(System.lineSeparator()),
        )
        val mock = File(dir, "mock.json")
        mock.writeText(
            """{"http": [{"url": "api.test/items", "status": 200, "body": "{\"n\": 3}"}], "answers": ["Baku"]}""",
        )
        val shown = mutableListOf<String>()
        val begun = System.currentTimeMillis()
        run(listOf("run", script.path, "--mock", mock.path, "--speed", "10000"), print = { shown += it })
        assertTrue("an hour of script time took ${System.currentTimeMillis() - begun} ms", System.currentTimeMillis() - begun < 20_000)
        assertTrue(shown.toString(), "Baku 200 3" in shown && "slept True" in shown)
        assertTrue(shown.toString(), shown.any { it.startsWith("[mock] GET other.test/ -> ") || "no answer prepared for GET other.test/" in it })
        assertTrue(shown.toString(), "404" in shown)
    }

    @Test
    fun `network requests are real and limited to public addresses unless local ones are allowed`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                while (reader.readLine().orEmpty().isNotEmpty()) Unit
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nlocal".toByteArray())
                client.getOutputStream().flush()
            }
        }
        val dir = project(
            "js", "main.js", "\"NETWORK_OUTBOUND\"",
            """
            try {
                var r = mf.http({url: "http://127.0.0.1:${server.localPort}/"});
                mf.log(r.status + " " + r.body);
            } catch (e) {
                mf.log(e.message);
            }
            """,
        )
        try {
            assertEquals(listOf("destination is not a public internet address", "script finished"), execute(dir.path))
            assertEquals(listOf("200 local", "script finished"), execute(dir.path, "--allow-local"))
        } finally {
            server.close()
        }
    }
}
