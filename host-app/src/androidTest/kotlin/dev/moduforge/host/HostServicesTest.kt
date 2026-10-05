package dev.moduforge.host

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.permission.ConsentDecision
import dev.moduforge.core.permission.DefaultPermissionBroker
import dev.moduforge.core.pkg.ModulePackageWriter
import dev.moduforge.core.pkg.SigningKey
import dev.moduforge.host.runtime.ModuleLogs
import dev.moduforge.sandbox.KeystoreStorageCipher
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sandbox.ModuleNetworkRelay
import dev.moduforge.sandbox.ModulePackageStore
import dev.moduforge.sandbox.ModuleStorage
import dev.moduforge.sandbox.PackageInspection
import dev.moduforge.sandbox.SandboxModuleRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Network and storage as seen by a script module in a real sandbox.
 * The network cases need the device to be online.
 */
@RunWith(AndroidJUnit4::class)
class HostServicesTest {

    private val moduleId = "com.example.services"
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = File(context.cacheDir, "services-test-modules")

    private val registry = MemoryRegistry()
    private val grants = MemoryGrants()
    private val audit = MemoryAudit()
    private val logs = ModuleLogs()
    private val packages = ModulePackageStore(storage)
    private val dataRoot = File(storage, "data")
    private val moduleStorage = ModuleStorage(dataRoot, KeystoreStorageCipher("moduforge.test-storage"))
    private val broker = DefaultPermissionBroker(registry, grants, { ConsentDecision.Allow() }, audit)
    private val notified = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val notifier = dev.moduforge.core.module.ModuleNotifier { id, name, title, text -> notified += "$id|$name|$title|$text"; true }
    private val runtime = SandboxModuleRuntime(
        context, packages, broker, logs, { _, _ -> }, ModuleNetworkRelay(broker, audit), moduleStorage, notifier, { _, _, question, _ -> "answer to $question" }, scope,
    )
    private val manager = ModuleManager(registry, grants, audit, runtime)
    private val installer = ModuleInstaller(packages, registry, manager, moduleStorage)

    private val manifest = """
        {"id":"$moduleId","name":"Services","version":"1.0.0","sdkRange":">=1.0.0 <2.0.0",
         "runtime":"lua","entry":"main.lua","permissions":["NETWORK_OUTBOUND","FILE_SANDBOXED","NOTIFICATIONS"]}
    """.trimIndent()

    private val script = """
        local function show(name, value, err) mf.log("T " .. name .. "=" .. tostring(value) .. "|" .. tostring(err)) end

        local res, err = mf.http{url = "https://example.com/"}
        show("before-grant", res, err)
        show("storage-before-grant", mf.storage.read("a.txt"))

        mf.request("NETWORK_OUTBOUND", "test")
        mf.request("FILE_SANDBOXED", "test")

        res, err = mf.http{url = "https://example.com/"}
        show("https", res and res.status, err)
        show("https-body", res and (res.body:find("Example Domain", 1, true) ~= nil), nil)

        res, err = mf.http{url = "http://example.com/", method = "HEAD"}
        show("http-head", res and res.status, err)

        res, err = mf.http{url = "http://192.168.1.1/"}
        show("lan", res, err)
        res, err = mf.http{url = "http://127.0.0.1:8080/"}
        show("loopback", res, err)
        res, err = mf.http{url = "https://wrong.host.badssl.com/"}
        show("bad-cert", res, err ~= nil)

        show("write", mf.storage.write("session/account.session", "SECRET-SESSION-BYTES"))
        show("read", mf.storage.read("session/account.session"))
        show("missing", mf.storage.read("nope"))
        show("list", table.concat(mf.storage.list(), ","))
        show("escape", mf.storage.write("../other/x", "1"))
        show("delete", mf.storage.delete("session/account.session"))
        show("after-delete", mf.storage.read("session/account.session"))
        mf.storage.write("keep.txt", "KEPT-PLAINTEXT-MARKER")

        show("notify-before-grant", mf.notify("Title", "Text"))
        mf.request("NOTIFICATIONS", "test")
        show("notify", mf.notify("Title", "Text"))

        local data = mf.json.decode('{"ok":true,"result":[{"id":5000000000,"text":"привет","nested":{"n":1.5}}],"none":null}')
        show("json-decode", tostring(data.ok) .. "," .. #data.result .. "," .. tostring(data.result[1].id) .. "," ..
            data.result[1].text .. "," .. tostring(data.result[1].nested.n) .. "," .. tostring(data.none))
        show("json-encode", mf.json.encode({chat_id = 5000000000, tags = {"a", "b"}, flag = false}))
        show("json-bad", pcall(mf.json.decode, "{oops"))
        show("urlencode", mf.urlencode("a b&c=д"))
        show("ask", mf.ask("token?", true))
        mf.log("T done")
        while true do mf.sleep(1) end
    """.trimIndent()

    @Before
    fun clean() {
        storage.deleteRecursively()
    }

    @After
    fun tearDown() {
        runtime.kill(moduleId)
        scope.cancel()
        storage.deleteRecursively()
    }

    @Test
    fun scriptReachesNetworkAndStorageOnlyThroughGrantedHostServices() = runBlocking {
        val bytes = ByteArrayOutputStream().also {
            ModulePackageWriter.write(it, manifest, mapOf("code/main.lua" to script.toByteArray()), SigningKey.generate())
        }.toByteArray()
        val inspection = installer.inspect { bytes.inputStream() } as PackageInspection.Ready
        assertTrue(installer.install(inspection) is InstallResult.Installed)
        assertTrue(manager.setEnabled(moduleId, true))
        assertTrue(manager.start(moduleId))

        val report = withTimeout(120_000) {
            logs.observe(moduleId).first { lines -> lines.any { it.message == "T done" } }
        }.map { it.message }
        fun line(name: String) = report.firstOrNull { it.startsWith("T $name=") } ?: error("no '$name' in $report")

        assertEquals("T before-grant=nil|NETWORK_OUTBOUND is not granted", line("before-grant"))
        assertEquals("T storage-before-grant=nil|FILE_SANDBOXED is not granted", line("storage-before-grant"))
        assertEquals("T https=200|nil", line("https"))
        assertEquals("T https-body=true|nil", line("https-body"))
        assertEquals("T http-head=200|nil", line("http-head"))
        assertEquals("T lan=nil|destination is not a public internet address", line("lan"))
        assertEquals("T loopback=nil|destination is not a public internet address", line("loopback"))
        assertEquals("T bad-cert=nil|true", line("bad-cert"))

        assertEquals("T write=true|nil", line("write"))
        assertEquals("T read=SECRET-SESSION-BYTES|nil", line("read"))
        assertEquals("T missing=nil|nil", line("missing"))
        assertEquals("T list=session/account.session|nil", line("list"))
        assertTrue(line("escape"), line("escape").startsWith("T escape=nil|"))
        assertEquals("T delete=true|nil", line("delete"))
        assertEquals("T after-delete=nil|nil", line("after-delete"))

        assertEquals("T notify-before-grant=nil|NOTIFICATIONS is not granted", line("notify-before-grant"))
        assertEquals("T notify=true|nil", line("notify"))
        assertEquals(listOf("$moduleId|Services|Title|Text"), notified.toList())
        assertEquals("T json-decode=true,1,5000000000,привет,1.5,nil|nil", line("json-decode"))
        val encoded = line("json-encode").removePrefix("T json-encode=").removeSuffix("|nil")
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"chat_id":5000000000,"tags":["a","b"],"flag":false}"""),
            kotlinx.serialization.json.Json.parseToJsonElement(encoded),
        )
        assertTrue(line("json-bad"), line("json-bad").startsWith("T json-bad=false|"))
        assertEquals("T urlencode=a%20b%26c%3D%D0%B4|nil", line("urlencode"))
        assertEquals("T ask=answer to token?|nil", line("ask"))

        val connected = audit.events().filter { it.type == AuditEventType.NETWORK_CONNECTED }.map { it.target }
        assertEquals(listOf("example.com:443", "example.com:80"), connected)
        assertTrue(audit.events().any { it.type == AuditEventType.NETWORK_REFUSED && it.target == "192.168.1.1:80" })

        val stored = File(dataRoot, "$moduleId/keep.txt")
        assertTrue(stored.isFile)
        assertFalse("module data must be encrypted at rest", "KEPT-PLAINTEXT-MARKER" in stored.readBytes().decodeToString())
        assertFalse(File(dataRoot, "other").exists())

        assertTrue(manager.stop(moduleId))
        assertTrue(manager.setEnabled(moduleId, false))
        assertTrue(installer.uninstall(moduleId))
        assertFalse("uninstall removes module data", File(dataRoot, moduleId).exists())
    }
}
