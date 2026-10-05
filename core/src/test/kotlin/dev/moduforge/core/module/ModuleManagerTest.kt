package dev.moduforge.core.module

import dev.moduforge.core.FakeRuntime
import dev.moduforge.core.InMemoryAuditLog
import dev.moduforge.core.InMemoryGrantStore
import dev.moduforge.core.InMemoryModuleRegistry
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.manifest
import dev.moduforge.core.permission.GrantRecord
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.SemVer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManagerTest {

    private val id = "com.example.tool"
    private val registry = InMemoryModuleRegistry()
    private val grants = InMemoryGrantStore()
    private val audit = InMemoryAuditLog()
    private val runtime = FakeRuntime()
    private val manager = ModuleManager(
        registry, grants, audit, runtime,
        hostSdkVersion = SemVer(1, 0, 0),
        callbackTimeoutMs = 1_000,
        clock = { 42L },
    )

    private val json = ModuleManifests.encode(manifest(permissions = listOf(Capability.NETWORK_OUTBOUND)))

    private fun auditTypes() = audit.events.map { it.type }

    private suspend fun installed(state: ModuleState = ModuleState.INSTALLED) {
        manager.install(json)
        if (state != ModuleState.INSTALLED) manager.setEnabled(id, true)
        if (state == ModuleState.RUNNING) manager.start(id)
        runtime.calls.clear()
    }

    @Test
    fun `installed module is disabled, holds no grants and leaves no sandbox behind`() = runTest {
        val result = manager.install(json) as InstallResult.Installed

        assertEquals(ModuleState.INSTALLED, result.record.state)
        assertEquals(result.record, registry.find(id))
        assertTrue(grants.all.isEmpty())
        assertEquals(listOf(AuditEventType.MODULE_INSTALLED), auditTypes())
        assertEquals(listOf("launch", "INSTALL", "kill"), runtime.calls)
        assertFalse(runtime.isAlive(id))
    }

    @Test
    fun `malformed manifest is rejected and audited without running code`() = runTest {
        val result = manager.install("{}")

        assertTrue(result is InstallResult.Rejected)
        assertEquals(AuditEventType.MODULE_REJECTED, audit.events.single().type)
        assertEquals(ModuleManager.UNPARSED_MODULE_ID, audit.events.single().moduleId)
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `module built for another SDK major is rejected`() = runTest {
        val incompatible = ModuleManifests.encode(manifest(sdkRange = ">=2.0.0 <3.0.0"))

        assertTrue(manager.install(incompatible) is InstallResult.Rejected)
        assertNull(registry.find(id))
    }

    @Test
    fun `module for a runtime the host lacks is rejected`() = runTest {
        val python = ModuleManifests.encode(
            manifest().copy(runtime = dev.moduforge.sdk.ModuleRuntimeKind.PYTHON, entry = "main.py"),
        )

        assertTrue(manager.install(python) is InstallResult.Rejected)
        assertNull(registry.find(id))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `origin of a package is audited`() = runTest {
        manager.install(json, signer = "abc123")

        assertTrue("signed by abc123" in audit.events.single().detail)
    }

    private fun version(v: String, permissions: List<Capability> = listOf(Capability.NETWORK_OUTBOUND)) =
        ModuleManifests.encode(manifest(permissions = permissions).copy(version = v))

    @Test
    fun `update keeps state and grants and drops grants for removed permissions`() = runTest {
        manager.install(version("1.0.0", listOf(Capability.NETWORK_OUTBOUND, Capability.CLIPBOARD)), signer = "key")
        manager.setEnabled(id, true)
        grants.save(GrantRecord(id, Capability.NETWORK_OUTBOUND, grantedAtMs = 0))
        grants.save(GrantRecord(id, Capability.CLIPBOARD, grantedAtMs = 0))
        var replaced = false

        val result = manager.update(version("1.1.0"), signer = "key") { replaced = true; true }

        assertTrue(result is InstallResult.Installed)
        assertTrue(replaced)
        assertEquals("1.1.0", registry.find(id)?.manifest?.version)
        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertEquals(listOf(Capability.NETWORK_OUTBOUND), grants.all.map { it.capability })
        assertEquals(AuditEventType.MODULE_UPDATED, audit.events.last().type)
    }

    @Test
    fun `update is refused for another key, an older version, a running or a bundled module`() = runTest {
        manager.install(version("1.1.0"), signer = "key")
        var replaced = false
        val replace: suspend () -> Boolean = { replaced = true; true }

        assertTrue(manager.update(version("1.2.0"), signer = "other", replace) is InstallResult.Rejected)
        assertTrue(manager.update(version("1.0.0"), signer = "key", replace) is InstallResult.Rejected)
        manager.setEnabled(id, true)
        manager.start(id)
        assertTrue(manager.update(version("1.2.0"), signer = "key", replace) is InstallResult.Rejected)
        assertFalse(replaced)
        assertEquals("1.1.0", registry.find(id)?.manifest?.version)

        val bundled = ModuleManifests.encode(manifest(id = "com.example.bundled"))
        manager.install(bundled)
        assertTrue(manager.update(bundled, signer = "key", replace) is InstallResult.Rejected)
    }

    @Test
    fun `auto-start launches only enabled modules that may run in the background`() = runTest {
        val background = listOf(Capability.BACKGROUND_EXECUTION)
        listOf("com.example.a", "com.example.b", "com.example.c", "com.example.d").forEach {
            manager.install(ModuleManifests.encode(manifest(id = it, permissions = background)))
            grants.save(GrantRecord(it, Capability.BACKGROUND_EXECUTION, grantedAtMs = 0))
        }
        listOf("com.example.a", "com.example.b", "com.example.c").forEach { manager.setEnabled(it, true) }
        listOf("com.example.a", "com.example.c", "com.example.d").forEach { manager.setAutoStart(it, true) }
        grants.delete("com.example.c", Capability.BACKGROUND_EXECUTION)

        manager.startAutoStartModules()

        assertEquals(ModuleState.RUNNING, registry.find("com.example.a")?.state)
        assertEquals("not marked", ModuleState.ENABLED, registry.find("com.example.b")?.state)
        assertEquals("no background grant", ModuleState.ENABLED, registry.find("com.example.c")?.state)
        assertEquals("disabled", ModuleState.INSTALLED, registry.find("com.example.d")?.state)
    }

    @Test
    fun `duplicate installation is rejected`() = runTest {
        manager.install(json)

        assertTrue(manager.install(json) is InstallResult.Rejected)
    }

    @Test
    fun `enable and disable deliver callbacks in transient sandboxes`() = runTest {
        installed()

        assertTrue(manager.setEnabled(id, true))
        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertTrue(manager.setEnabled(id, false))
        assertEquals(ModuleState.INSTALLED, registry.find(id)?.state)

        assertEquals(listOf("launch", "ENABLE", "kill", "launch", "DISABLE", "kill"), runtime.calls)
        assertFalse(manager.setEnabled("com.example.missing", true))
        assertEquals(
            listOf(AuditEventType.MODULE_INSTALLED, AuditEventType.MODULE_ENABLED, AuditEventType.MODULE_DISABLED),
            auditTypes(),
        )
    }

    @Test
    fun `start keeps the sandbox alive and stop destroys it`() = runTest {
        installed(ModuleState.ENABLED)

        assertTrue(manager.start(id))
        assertEquals(ModuleState.RUNNING, registry.find(id)?.state)
        assertTrue(runtime.isAlive(id))

        assertTrue(manager.stop(id))
        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertFalse(runtime.isAlive(id))
        assertEquals(listOf("launch", "START", "STOP", "kill"), runtime.calls)
        assertEquals(listOf(AuditEventType.MODULE_STARTED, AuditEventType.MODULE_STOPPED), auditTypes().takeLast(2))
    }

    @Test
    fun `disabled module cannot be started`() = runTest {
        installed()

        assertFalse(manager.start(id))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test
    fun `failed start leaves the module enabled with no sandbox`() = runTest {
        installed(ModuleState.ENABLED)
        runtime.failingPhases += LifecyclePhase.START

        assertFalse(manager.start(id))

        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertFalse(runtime.isAlive(id))
        assertEquals(AuditEventType.MODULE_CALLBACK_FAILED, audit.events.last().type)
    }

    @Test
    fun `launch failure is audited`() = runTest {
        installed(ModuleState.ENABLED)
        runtime.launchFailure = "package missing"

        assertFalse(manager.start(id))
        assertEquals("START: package missing", audit.events.last().detail)
    }

    @Test
    fun `module that ignores onStop is killed after the timeout`() = runTest {
        installed(ModuleState.RUNNING)
        runtime.hangingPhases += LifecyclePhase.STOP

        assertTrue(manager.stop(id))

        assertFalse(runtime.isAlive(id))
        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertEquals(
            listOf(AuditEventType.MODULE_CALLBACK_FAILED, AuditEventType.MODULE_STOPPED),
            auditTypes().takeLast(2),
        )
    }

    @Test
    fun `kill destroys the sandbox without calling the module`() = runTest {
        installed(ModuleState.RUNNING)

        assertTrue(manager.kill(id))

        assertEquals(listOf("kill"), runtime.calls)
        assertFalse(runtime.isAlive(id))
        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertEquals(AuditEventType.MODULE_KILLED, audit.events.last().type)
    }

    @Test
    fun `unexpected exit returns the module to enabled`() = runTest {
        installed(ModuleState.RUNNING)
        runtime.alive.clear()

        manager.onUnexpectedExit(id)

        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
        assertEquals(AuditEventType.MODULE_CRASHED, audit.events.last().type)
    }

    @Test
    fun `recover resets modules left running by a dead host`() = runTest {
        installed(ModuleState.RUNNING)
        runtime.alive.clear()

        manager.recover()

        assertEquals(ModuleState.ENABLED, registry.find(id)?.state)
    }

    @Test
    fun `running module cannot be disabled or uninstalled`() = runTest {
        installed(ModuleState.RUNNING)

        assertFalse(manager.setEnabled(id, false))
        assertFalse(manager.uninstall(id))
    }

    @Test
    fun `failing callback does not block disable or uninstall`() = runTest {
        installed(ModuleState.ENABLED)
        runtime.failingPhases += listOf(LifecyclePhase.DISABLE, LifecyclePhase.UNINSTALL)

        assertTrue(manager.setEnabled(id, false))
        assertTrue(manager.uninstall(id))
        assertNull(registry.find(id))
    }

    @Test
    fun `uninstall removes the module and its grants`() = runTest {
        installed()
        grants.save(GrantRecord(id, Capability.NETWORK_OUTBOUND, grantedAtMs = 0))
        grants.save(GrantRecord("com.example.other", Capability.CLIPBOARD, grantedAtMs = 0))

        assertTrue(manager.uninstall(id))

        assertNull(registry.find(id))
        assertEquals(listOf("com.example.other"), grants.all.map { it.moduleId })
        assertEquals(listOf("launch", "UNINSTALL", "kill"), runtime.calls)
        assertEquals(AuditEventType.MODULE_UNINSTALLED, audit.events.last().type)
    }

    @Test
    fun `lifecycle transitions follow the callback order`() {
        assertTrue(ModuleState.INSTALLED.canTransitionTo(ModuleState.ENABLED))
        assertTrue(ModuleState.ENABLED.canTransitionTo(ModuleState.RUNNING))
        assertTrue(ModuleState.RUNNING.canTransitionTo(ModuleState.ENABLED))
        assertTrue(ModuleState.ENABLED.canTransitionTo(ModuleState.INSTALLED))
        assertFalse(ModuleState.INSTALLED.canTransitionTo(ModuleState.RUNNING))
        assertFalse(ModuleState.RUNNING.canTransitionTo(ModuleState.INSTALLED))
    }
}
