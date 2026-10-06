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
        val error = assertThrows(UsageError::class.java) { execute(dir.path) }
        assertTrue(error.message, "script failed" in error.message!! && "main.js:2" in error.message!!)
        assertThrows(UsageError::class.java) { execute(File(temp.root, "nowhere").path) }
        assertThrows(UsageError::class.java) { execute(temp.newFile("notes.txt").path) }
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
