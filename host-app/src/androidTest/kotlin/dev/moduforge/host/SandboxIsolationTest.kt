package dev.moduforge.host

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.permission.ConsentDecision
import dev.moduforge.core.permission.DefaultPermissionBroker
import dev.moduforge.core.permission.GrantRecord
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.host.runtime.ModuleLogs
import dev.moduforge.sandbox.KeystoreStorageCipher
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sandbox.ModuleNetworkRelay
import dev.moduforge.sandbox.ModuleStorage
import dev.moduforge.sandbox.ModulePackageStore
import dev.moduforge.sandbox.SandboxModuleRuntime
import dev.moduforge.sdk.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the sandbox probe module in a real isolated process and checks what it managed to do.
 * Uses the production runtime with throwaway storage, so the installed host's data is untouched.
 */
@RunWith(AndroidJUnit4::class)
class SandboxIsolationTest {

    private val probeId = "dev.moduforge.modules.probe"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = File(context.cacheDir, "isolation-test-modules")

    private val registry = MemoryRegistry()
    private val grants = MemoryGrants()
    private val audit = MemoryAudit()
    private val logs = ModuleLogs()
    private val packages = ModulePackageStore(storage)
    private val moduleStorage = ModuleStorage(File(storage, "data"), KeystoreStorageCipher("moduforge.test-storage"))
    private val broker = DefaultPermissionBroker(registry, grants, { ConsentDecision.Deny }, audit)
    private val runtime = SandboxModuleRuntime(context, packages, broker, logs, { _, _ -> }, ModuleNetworkRelay(broker, audit), moduleStorage, { _, _, _, _ -> true }, { _, _, _, _ -> null }, scope)
    private val manager = ModuleManager(registry, grants, audit, runtime)
    private val installer = ModuleInstaller(packages, registry, manager, moduleStorage)

    @Before
    fun installProbe() = runBlocking {
        storage.deleteRecursively()
        val result = installer.installTrusted { instrumentation.context.assets.open("modules/sandbox-probe.apk") }
        assertTrue("probe was not installed: $result", result is InstallResult.Installed)
        assertTrue(manager.setEnabled(probeId, true))
    }

    @After
    fun tearDown() {
        runtime.kill(probeId)
        scope.cancel()
        storage.deleteRecursively()
    }

    @Test
    fun moduleWithoutGrantsCannotReachNetworkFilesOrClipboard() = runBlocking {
        assertTrue(manager.start(probeId))

        val report = withTimeout(60_000) {
            logs.observe(probeId).first { lines -> lines.any { it.message == "PROBE done" } }
        }.map { it.message }

        val leaked = report.filter { "LEAKED" in it }
        assertTrue("sandbox escape: $leaked", leaked.isEmpty())
        listOf("network", "dns", "host-file-read", "host-file-write", "host-dir-list", "shared-storage", "clipboard-read", "window-service")
            .forEach { name ->
                assertTrue("no BLOCKED report for $name in $report", report.any { it.startsWith("PROBE $name=BLOCKED") })
            }
        assertTrue(report.toString(), report.any { it.startsWith("PROBE undeclared-capability=") && "NOT_DECLARED" in it })
        assertTrue(grants.all().isEmpty())
        assertTrue(audit.events().any { it.type == AuditEventType.CAPABILITY_DENIED && it.capability == Capability.NETWORK_OUTBOUND })
    }

    @Test
    fun killDestroysTheSandboxProcess() = runBlocking {
        assertTrue(manager.start(probeId))
        assertTrue("sandbox process not found in:\n${sandboxProcesses()}", sandboxProcesses().isNotEmpty())

        assertTrue(manager.kill(probeId))

        withTimeout(10_000) {
            while (sandboxProcesses().isNotEmpty()) delay(200)
        }
        assertFalse(runtime.isAlive(probeId))
        assertEquals(AuditEventType.MODULE_KILLED, audit.events().last().type)
    }

    @Test
    fun stopLeavesNoProcessBehind() = runBlocking {
        assertTrue("start failed: ${audit.events().map { "${it.type} ${it.detail}" }}", manager.start(probeId))
        assertTrue(manager.stop(probeId))

        withTimeout(10_000) {
            while (sandboxProcesses().isNotEmpty()) delay(200)
        }
    }

    /** Process list as seen by the shell user, which is not subject to per-UID /proc hiding. */
    private fun sandboxProcesses(): List<String> {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("ps -A -o PID,NAME")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
            .filter { "${context.packageName}:sandbox:" in it && ":$probeId.s" in it }
    }
}

internal class MemoryRegistry : ModuleRegistry {
    private val state = MutableStateFlow<Map<String, ModuleRecord>>(emptyMap())

    override suspend fun find(moduleId: String) = state.value[moduleId]

    override fun observe(moduleId: String): Flow<ModuleRecord?> = state.map { it[moduleId] }

    override fun observeAll(): Flow<List<ModuleRecord>> = state.map { it.values.toList() }

    override suspend fun save(record: ModuleRecord) = state.update { it + (record.id to record) }

    override suspend fun delete(moduleId: String) = state.update { it - moduleId }
}

internal class MemoryGrants : GrantStore {
    private val state = MutableStateFlow<List<GrantRecord>>(emptyList())

    fun all() = state.value

    override suspend fun find(moduleId: String, capability: Capability) =
        state.value.firstOrNull { it.moduleId == moduleId && it.capability == capability }

    override fun observe(moduleId: String): Flow<List<GrantRecord>> =
        state.map { all -> all.filter { it.moduleId == moduleId } }

    override fun observeAll(): Flow<List<GrantRecord>> = state

    override suspend fun save(record: GrantRecord) {
        delete(record.moduleId, record.capability)
        state.update { it + record }
    }

    override suspend fun delete(moduleId: String, capability: Capability) =
        state.update { all -> all.filterNot { it.moduleId == moduleId && it.capability == capability } }

    override suspend fun deleteAll(moduleId: String) = state.update { all -> all.filterNot { it.moduleId == moduleId } }
}

internal class MemoryAudit : AuditLog {
    private val state = MutableStateFlow<List<AuditEvent>>(emptyList())

    fun events() = state.value

    override suspend fun record(event: AuditEvent) = state.update { it + event }

    override fun observe(moduleId: String?): Flow<List<AuditEvent>> = state.map { it.reversed() }
}
