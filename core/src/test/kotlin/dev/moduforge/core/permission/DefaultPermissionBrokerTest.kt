package dev.moduforge.core.permission

import dev.moduforge.core.InMemoryAuditLog
import dev.moduforge.core.InMemoryGrantStore
import dev.moduforge.core.InMemoryModuleRegistry
import dev.moduforge.core.RecordingPrompter
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.manifest
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleState
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.DenialReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPermissionBrokerTest {

    private val moduleId = "com.example.tool"
    private val registry = InMemoryModuleRegistry()
    private val grants = InMemoryGrantStore()
    private val audit = InMemoryAuditLog()
    private val prompter = RecordingPrompter()
    private val broker = DefaultPermissionBroker(registry, grants, prompter, audit, clock = { 1_000L })

    private suspend fun install(vararg permissions: Capability, state: ModuleState = ModuleState.ENABLED) {
        registry.save(ModuleRecord(manifest(moduleId, permissions.toList()), state, installedAtMs = 0))
    }

    private suspend fun request(capability: Capability, target: String? = null) =
        broker.request(moduleId, CapabilityRequest(capability, "because", target))

    private fun auditTypes() = audit.events.map { it.type }

    @Test
    fun `module without grants has no capability at all`() = runTest {
        install(*Capability.entries.toTypedArray())

        Capability.entries.forEach { capability ->
            assertFalse("$capability", broker.isGranted(moduleId, capability, target = "192.168.1.10"))
        }
    }

    @Test
    fun `unknown module is denied without a prompt`() = runTest {
        assertEquals(CapabilityResult.Denied(DenialReason.MODULE_UNKNOWN), request(Capability.NETWORK_OUTBOUND))
        assertTrue(prompter.prompts.isEmpty())
    }

    @Test
    fun `disabled module is denied even with a stored grant`() = runTest {
        install(Capability.CLIPBOARD, state = ModuleState.INSTALLED)
        grants.save(GrantRecord(moduleId, Capability.CLIPBOARD, grantedAtMs = 0))

        assertEquals(CapabilityResult.Denied(DenialReason.MODULE_NOT_ENABLED), request(Capability.CLIPBOARD))
        assertFalse(broker.isGranted(moduleId, Capability.CLIPBOARD))
        assertTrue(prompter.prompts.isEmpty())
    }

    @Test
    fun `undeclared capability is denied without a prompt`() = runTest {
        install(Capability.NOTIFICATIONS)
        prompter.decision = ConsentDecision.Allow()

        assertEquals(CapabilityResult.Denied(DenialReason.NOT_DECLARED), request(Capability.NETWORK_OUTBOUND))
        assertTrue(prompter.prompts.isEmpty())
        assertTrue(grants.all.isEmpty())
    }

    @Test
    fun `stored grant for an undeclared capability is not honoured`() = runTest {
        install(Capability.NOTIFICATIONS)
        grants.save(GrantRecord(moduleId, Capability.NETWORK_OUTBOUND, grantedAtMs = 0))

        assertFalse(broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND))
        assertEquals(CapabilityResult.Denied(DenialReason.NOT_DECLARED), request(Capability.NETWORK_OUTBOUND))
    }

    @Test
    fun `user denial is not cached`() = runTest {
        install(Capability.NETWORK_OUTBOUND)

        assertEquals(CapabilityResult.Denied(DenialReason.USER_DENIED), request(Capability.NETWORK_OUTBOUND))
        assertFalse(broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND))

        prompter.decision = ConsentDecision.Allow()
        assertEquals(CapabilityResult.Granted, request(Capability.NETWORK_OUTBOUND))
        assertEquals(2, prompter.prompts.size)
    }

    @Test
    fun `user consent is cached and prompts once`() = runTest {
        install(Capability.NETWORK_OUTBOUND)
        prompter.decision = ConsentDecision.Allow()

        assertEquals(CapabilityResult.Granted, request(Capability.NETWORK_OUTBOUND))
        assertEquals(CapabilityResult.Granted, request(Capability.NETWORK_OUTBOUND))

        assertEquals(1, prompter.prompts.size)
        assertEquals("because", prompter.prompts.single().rationale)
        assertTrue(broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND))
    }

    @Test
    fun `grant covers only the requested capability`() = runTest {
        install(Capability.NETWORK_OUTBOUND, Capability.CLIPBOARD)
        prompter.decision = ConsentDecision.Allow()
        request(Capability.NETWORK_OUTBOUND)

        assertFalse(broker.isGranted(moduleId, Capability.CLIPBOARD))
    }

    @Test
    fun `revocation removes the grant and prompts again`() = runTest {
        install(Capability.CLIPBOARD)
        prompter.decision = ConsentDecision.Allow()
        request(Capability.CLIPBOARD)

        broker.revoke(moduleId, Capability.CLIPBOARD)

        assertFalse(broker.isGranted(moduleId, Capability.CLIPBOARD))
        assertEquals(AuditEventType.CAPABILITY_REVOKED, audit.events.last().type)
        request(Capability.CLIPBOARD)
        assertEquals(2, prompter.prompts.size)
    }

    @Test
    fun `intrusive capability requires a target`() = runTest {
        install(Capability.LOCAL_NETWORK_SCAN)
        prompter.decision = ConsentDecision.Allow(targetAuthorizationConfirmed = true)

        assertEquals(CapabilityResult.Denied(DenialReason.TARGET_REQUIRED), request(Capability.LOCAL_NETWORK_SCAN))
        assertEquals(CapabilityResult.Denied(DenialReason.TARGET_REQUIRED), request(Capability.LOCAL_NETWORK_SCAN, "  "))
        assertTrue(prompter.prompts.isEmpty())
    }

    @Test
    fun `intrusive capability is denied without authorization confirmation`() = runTest {
        install(Capability.LOCAL_NETWORK_SCAN)
        prompter.decision = ConsentDecision.Allow(targetAuthorizationConfirmed = false)

        assertEquals(
            CapabilityResult.Denied(DenialReason.TARGET_NOT_AUTHORIZED),
            request(Capability.LOCAL_NETWORK_SCAN, "192.168.1.0/24"),
        )
        assertTrue(grants.all.isEmpty())
    }

    @Test
    fun `target authorization is per target`() = runTest {
        install(Capability.LOCAL_NETWORK_SCAN)
        prompter.decision = ConsentDecision.Allow(targetAuthorizationConfirmed = true)

        assertEquals(CapabilityResult.Granted, request(Capability.LOCAL_NETWORK_SCAN, "Lab.Example "))
        assertEquals("lab.example", prompter.prompts.single().target)
        assertTrue(broker.isGranted(moduleId, Capability.LOCAL_NETWORK_SCAN, "lab.example"))
        assertFalse(broker.isGranted(moduleId, Capability.LOCAL_NETWORK_SCAN, "other.example"))
        assertFalse(broker.isGranted(moduleId, Capability.LOCAL_NETWORK_SCAN, target = null))

        assertEquals(CapabilityResult.Granted, request(Capability.LOCAL_NETWORK_SCAN, "lab.example"))
        assertEquals(1, prompter.prompts.size)

        prompter.decision = ConsentDecision.Deny
        assertEquals(CapabilityResult.Denied(DenialReason.USER_DENIED), request(Capability.LOCAL_NETWORK_SCAN, "other.example"))
        assertEquals(setOf("lab.example"), grants.all.single().authorizedTargets)
    }

    @Test
    fun `every decision is audited`() = runTest {
        install(Capability.NETWORK_OUTBOUND, Capability.LOCAL_NETWORK_SCAN)

        request(Capability.CLIPBOARD)
        request(Capability.NETWORK_OUTBOUND)
        prompter.decision = ConsentDecision.Allow(targetAuthorizationConfirmed = true)
        request(Capability.NETWORK_OUTBOUND)
        request(Capability.NETWORK_OUTBOUND)
        request(Capability.LOCAL_NETWORK_SCAN, "10.0.0.5")

        assertEquals(
            listOf(
                AuditEventType.CAPABILITY_REQUESTED, AuditEventType.CAPABILITY_DENIED,
                AuditEventType.CAPABILITY_REQUESTED, AuditEventType.CAPABILITY_DENIED,
                AuditEventType.CAPABILITY_REQUESTED, AuditEventType.CAPABILITY_GRANTED,
                AuditEventType.CAPABILITY_REQUESTED, AuditEventType.CAPABILITY_GRANTED,
                AuditEventType.CAPABILITY_REQUESTED, AuditEventType.TARGET_AUTHORIZED, AuditEventType.CAPABILITY_GRANTED,
            ),
            auditTypes(),
        )
        assertEquals(DenialReason.NOT_DECLARED.name, audit.events[1].detail)
        assertEquals(DenialReason.USER_DENIED.name, audit.events[3].detail)
        assertEquals("10.0.0.5", audit.events.last().target)
        assertTrue(audit.events.all { it.moduleId == moduleId && it.timestampMs == 1_000L })
    }

    @Test
    fun `up-front grants cover declared, missing, target-free capabilities only`() = runTest {
        install(Capability.NETWORK_OUTBOUND, Capability.FILE_SANDBOXED, Capability.LOCAL_NETWORK_SCAN)
        grants.save(GrantRecord(moduleId, Capability.FILE_SANDBOXED, grantedAtMs = 0))
        assertEquals(listOf(Capability.NETWORK_OUTBOUND), broker.grantableUpFront(moduleId))

        broker.grantByUser(moduleId, setOf(Capability.NETWORK_OUTBOUND, Capability.CLIPBOARD, Capability.LOCAL_NETWORK_SCAN))

        assertTrue(broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND))
        assertFalse("undeclared", broker.isGranted(moduleId, Capability.CLIPBOARD))
        assertFalse("needs a target", broker.isGranted(moduleId, Capability.LOCAL_NETWORK_SCAN, "10.0.0.1"))
        assertEquals(listOf(AuditEventType.CAPABILITY_GRANTED), auditTypes())
        assertEquals(CapabilityResult.Granted, request(Capability.NETWORK_OUTBOUND))
        assertTrue(prompter.prompts.isEmpty())
        assertTrue(broker.grantableUpFront(moduleId).isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `concurrent requests produce one pending prompt at a time`() = runTest {
        install(Capability.NETWORK_OUTBOUND)
        val answer = CompletableDeferred<ConsentDecision>()
        var prompts = 0
        val broker = DefaultPermissionBroker(registry, grants, { prompts++; answer.await() }, audit)

        val first = async { broker.request(moduleId, CapabilityRequest(Capability.NETWORK_OUTBOUND, "a")) }
        val second = async { broker.request(moduleId, CapabilityRequest(Capability.NETWORK_OUTBOUND, "b")) }
        runCurrent()
        assertEquals(1, prompts)

        answer.complete(ConsentDecision.Allow())
        assertEquals(CapabilityResult.Granted, first.await())
        assertEquals(CapabilityResult.Granted, second.await())
        assertEquals(1, prompts)
    }
}
