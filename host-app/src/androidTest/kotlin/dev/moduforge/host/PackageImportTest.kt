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
import dev.moduforge.sandbox.ModuleStorage
import dev.moduforge.sandbox.ModulePackageStore
import dev.moduforge.sandbox.PackageInspection
import dev.moduforge.sandbox.SandboxModuleRuntime
import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.core.module.StopRequest
import dev.moduforge.sdk.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Imports signed `.mfrg` packages through the production installer and runs a script module in the sandbox. */
@RunWith(AndroidJUnit4::class)
class PackageImportTest {

    private val moduleId = "com.example.luabot"
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = File(context.cacheDir, "import-test-modules")

    private val registry = MemoryRegistry()
    private val grants = MemoryGrants()
    private val audit = MemoryAudit()
    private val logs = ModuleLogs(context)
    private val packages = ModulePackageStore(storage)
    private val moduleStorage = ModuleStorage(File(storage, "data"), KeystoreStorageCipher("moduforge.test-storage"))
    private val broker = DefaultPermissionBroker(registry, grants, { ConsentDecision.Allow() }, audit)
    private val runtime = SandboxModuleRuntime(context, packages, broker, logs, { _, _ -> }, ModuleNetworkRelay(broker, audit), moduleStorage, { _, _, _, _ -> true }, { _, _, _, _ -> null }, scope)
    private val manager = ModuleManager(registry, grants, audit, runtime)
    private val installer = ModuleInstaller(packages, registry, manager, moduleStorage)
    private val key = SigningKey.generate()

    private fun manifest(runtime: String = "lua", entry: String = "main.lua") = """
        {"id":"$moduleId","name":"Lua bot","version":"1.0.0","sdkRange":">=1.0.0 <2.0.0",
         "runtime":"$runtime","entry":"$entry","permissions":["NOTIFICATIONS"],
         "permissionReasons":{"NOTIFICATIONS":"Test."}}
    """.trimIndent()

    private val sources = mapOf(
        "code/main.lua" to """
            local util = require("lib.util")
            mf.log("SCRIPT sum=" .. util.sum(2, 3) .. " id=" .. mf.id)
            mf.log("SCRIPT declared=" .. tostring(mf.request("NOTIFICATIONS", "test")))
            local ok, why = mf.request("NETWORK_OUTBOUND", "test")
            mf.log("SCRIPT undeclared=" .. tostring(ok) .. ":" .. tostring(why))
            mf.log("SCRIPT os=" .. tostring(os) .. " io=" .. tostring(io) .. " luajava=" .. tostring(luajava))
            mf.log("SCRIPT done")
            while true do mf.sleep(1) end
        """.trimIndent().toByteArray(),
        "code/lib/util.lua" to "return { sum = function(a, b) return a + b end }".toByteArray(),
    )

    private fun pack(manifestJson: String = manifest(), files: Map<String, ByteArray> = sources): ByteArray =
        ByteArrayOutputStream().also { ModulePackageWriter.write(it, manifestJson, files, key) }.toByteArray()

    /** Rewrites the archive with one entry's content replaced, leaving the signature entry as it was. */
    private fun replaceEntry(archive: ByteArray, name: String, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipInputStream(archive.inputStream()).use { input ->
            ZipOutputStream(output).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(if (entry.name == name) content else input.readBytes())
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

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
    fun signedLuaPackageInstallsAndRunsInTheSandbox() = runBlocking {
        val bytes = pack()
        val inspection = installer.inspect { bytes.inputStream() } as PackageInspection.Ready
        assertEquals(key.fingerprint, inspection.signer)
        assertEquals(moduleId, inspection.manifest.id)
        assertNull("inspection must not install", registry.find(moduleId))

        assertTrue(installer.install(inspection) is InstallResult.Installed)
        assertTrue(key.fingerprint in audit.events().first { it.type == AuditEventType.MODULE_INSTALLED }.detail)
        assertTrue(manager.setEnabled(moduleId, true))
        assertTrue(manager.start(moduleId))

        val report = withTimeout(30_000) {
            logs.observe(moduleId).first { lines -> lines.any { it.message == "SCRIPT done" } }
        }.map { it.message }
        assertTrue(report.toString(), "SCRIPT sum=5 id=$moduleId" in report)
        assertTrue(report.toString(), "SCRIPT declared=true" in report)
        assertTrue(report.toString(), "SCRIPT undeclared=false:NOT_DECLARED" in report)
        assertTrue(report.toString(), "SCRIPT os=nil io=nil luajava=nil" in report)

        assertTrue("script keeps running after its report", runtime.isAlive(moduleId))
        assertTrue(manager.stop(moduleId))
    }

    @Test
    fun tamperedPackageIsRejectedBeforeInstallation() = runBlocking {
        val bytes = replaceEntry(pack(), "code/lib/util.lua", "return { sum = function() return 'pwned' end }".toByteArray())

        val inspection = installer.inspect { bytes.inputStream() }

        assertTrue(inspection.toString(), inspection is PackageInspection.Rejected)
        assertNull(registry.find(moduleId))
        assertEquals(AuditEventType.MODULE_REJECTED, audit.events().single().type)
        assertTrue(storage.walkTopDown().none { it.isFile })
    }

    @Test
    fun compiledModulePackedAsMfrgRuns() = runBlocking {
        val helloId = "dev.moduforge.modules.hello"
        val files = mutableMapOf<String, ByteArray>()
        var manifestJson = ""
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        ZipInputStream(assets.open("modules/hello.apk")).use { apk ->
            while (true) {
                val entry = apk.nextEntry ?: break
                when {
                    Regex("""classes\d*\.dex""").matches(entry.name) -> files["code/${entry.name}"] = apk.readBytes()
                    entry.name == "assets/moduforge.json" -> manifestJson = apk.readBytes().decodeToString()
                }
            }
        }
        try {
            val inspection = installer.inspect { pack(manifestJson, files).inputStream() } as PackageInspection.Ready
            assertTrue(installer.install(inspection) is InstallResult.Installed)
            assertTrue(manager.setEnabled(helloId, true))
            assertTrue(manager.start(helloId))

            val lines = withTimeout(30_000) {
                logs.observe(helloId).first { lines -> lines.any { it.message.startsWith("onStart: hello from Hello") } }
            }.map { it.message }
            assertTrue(lines.toString(), "onInstall" in lines && "onEnable" in lines)
            assertTrue(manager.stop(helloId))
        } finally {
            runtime.kill(helloId)
        }
    }

    @Test
    fun moduleWrittenOnTheDeviceRunsAndCanBeEdited() = runBlocking {
        val id = "local.note"
        suspend fun runOnce(expected: String) {
            assertTrue(manager.start(id))
            withTimeout(30_000) { logs.observe(id).first { lines -> lines.any { it.message == expected } } }
            // The script has ended; the host would stop the module on its request.
            manager.stop(id)
        }
        try {
            val first = LocalModules.manifest(id, "Note", "mf.log('v1')", emptySet(), previous = null)
            assertTrue(installer.saveLocal(first, "mf.log('v1')") is InstallResult.Installed)
            assertNull(registry.find(id)?.signer)
            assertEquals("mf.log('v1')", installer.readLocalSource(id))
            assertTrue(manager.setEnabled(id, true))
            runOnce("v1")

            val edited = "mf.storage.write('n', 'x')\nmf.log('v2')"
            val second = LocalModules.manifest(id, "Note", edited, emptySet(), first)
            assertTrue(installer.saveLocal(second, edited) is InstallResult.Installed)
            assertEquals("1.0.1", registry.find(id)?.manifest?.version)
            assertEquals(listOf(Capability.FILE_SANDBOXED), registry.find(id)?.manifest?.permissions)
            assertEquals(edited, installer.readLocalSource(id))
            runOnce("v2")

            val signed = ByteArrayOutputStream().also {
                ModulePackageWriter.write(it, manifest().replace(moduleId, id), sources, key)
            }.toByteArray()
            assertTrue("a package must not replace it", installer.inspect { signed.inputStream() } is PackageInspection.Rejected)
        } finally {
            runtime.kill(id)
        }
    }

    @Test
    fun scriptThatEndsAsksToBeStopped() = runBlocking {
        val files = mapOf("code/main.lua" to "mf.log('one-shot')".toByteArray())
        val inspection = installer.inspect { pack(files = files).inputStream() } as PackageInspection.Ready
        assertTrue(installer.install(inspection) is InstallResult.Installed)
        assertTrue(manager.setEnabled(moduleId, true))
        val request = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { runtime.stopRequests.first() } }

        assertTrue(manager.start(moduleId))

        assertEquals(StopRequest(moduleId, "script finished"), request.await())
        assertTrue(manager.stop(moduleId, request.await().reason))
        assertEquals("script finished", audit.events().last().detail)
    }

    @Test
    fun newerPackageFromTheSameAuthorUpdatesTheModule() = runBlocking {
        val first = installer.inspect { pack().inputStream() } as PackageInspection.Ready
        assertNull(first.installedVersion)
        assertTrue(installer.install(first) is InstallResult.Installed)

        val newer = manifest().replace("\"version\":\"1.0.0\"", "\"version\":\"1.1.0\"")
        val forged = ByteArrayOutputStream().also { ModulePackageWriter.write(it, newer, sources, SigningKey.generate()) }.toByteArray()
        assertTrue("another key must not replace the module", installer.inspect { forged.inputStream() } is PackageInspection.Rejected)

        val update = installer.inspect { pack(newer).inputStream() } as PackageInspection.Ready
        assertEquals("1.0.0", update.installedVersion)
        assertTrue(installer.install(update) is InstallResult.Installed)
        assertEquals("1.1.0", registry.find(moduleId)?.manifest?.version)
        assertEquals(AuditEventType.MODULE_UPDATED, audit.events().last().type)

        assertTrue(manager.setEnabled(moduleId, true))
        assertTrue("updated package must be runnable", manager.start(moduleId))
    }

    @Test
    fun packageForAnUnsupportedRuntimeIsRejected() = runBlocking {
        val bytes = pack(manifest(runtime = "python", entry = "main.py"), mapOf("code/main.py" to "print('hi')".toByteArray()))
        val inspection = installer.inspect { bytes.inputStream() } as PackageInspection.Ready

        val result = installer.install(inspection)

        assertTrue(result.toString(), result is InstallResult.Rejected && "not supported" in result.problems.single())
        assertNull(registry.find(moduleId))
        assertTrue(storage.walkTopDown().none { it.isFile })
    }
}
