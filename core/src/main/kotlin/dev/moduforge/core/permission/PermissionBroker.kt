package dev.moduforge.core.permission

import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.DenialReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single decision point for module capabilities. Host subsystems call [isGranted] before
 * performing an operation on behalf of a module; modules reach [request] through IPC.
 */
interface PermissionBroker {
    /** Decides a request, prompting the user when no cached grant covers it. Every call is audited. */
    suspend fun request(moduleId: String, request: CapabilityRequest): CapabilityResult

    /** Reports whether a capability is usable right now, without prompting or auditing. */
    suspend fun isGranted(moduleId: String, capability: Capability, target: String? = null): Boolean

    suspend fun revoke(moduleId: String, capability: Capability)

    /**
     * Capabilities the module declares but does not hold yet and that can be granted without
     * naming a target. These are what the host offers to the user before the module starts.
     */
    suspend fun grantableUpFront(moduleId: String): List<Capability>

    /**
     * Records grants the user chose in the host UI, outside of a module request.
     * Capabilities that are not in [grantableUpFront] are ignored.
     */
    suspend fun grantByUser(moduleId: String, capabilities: Set<Capability>)
}

class DefaultPermissionBroker(
    private val registry: ModuleRegistry,
    private val grants: GrantStore,
    private val prompter: ConsentPrompter,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : PermissionBroker {

    /** Serializes requests so that at most one consent prompt is pending. */
    private val mutex = Mutex()

    override suspend fun request(moduleId: String, request: CapabilityRequest): CapabilityResult = mutex.withLock {
        val capability = request.capability
        val target = if (capability.requiresTargetAuthorization) normalizeTarget(request.target) else null
        log(moduleId, AuditEventType.CAPABILITY_REQUESTED, capability, target, request.rationale)

        val record = registry.find(moduleId)
            ?: return deny(moduleId, capability, target, DenialReason.MODULE_UNKNOWN)
        if (record.state == ModuleState.INSTALLED) {
            return deny(moduleId, capability, target, DenialReason.MODULE_NOT_ENABLED)
        }
        if (capability !in record.manifest.permissions) {
            return deny(moduleId, capability, target, DenialReason.NOT_DECLARED)
        }
        if (capability.requiresTargetAuthorization && target == null) {
            return deny(moduleId, capability, null, DenialReason.TARGET_REQUIRED)
        }

        val existing = grants.find(moduleId, capability)
        if (existing != null && existing.covers(target)) {
            log(moduleId, AuditEventType.CAPABILITY_GRANTED, capability, target, "cached grant")
            return CapabilityResult.Granted
        }

        val prompt = ConsentPrompt(moduleId, record.manifest.name, capability, request.rationale, target)
        when (val decision = prompter.requestConsent(prompt)) {
            ConsentDecision.Deny -> deny(moduleId, capability, target, DenialReason.USER_DENIED)
            is ConsentDecision.Allow -> {
                if (target != null && !decision.targetAuthorizationConfirmed) {
                    return deny(moduleId, capability, target, DenialReason.TARGET_NOT_AUTHORIZED)
                }
                grants.save(
                    GrantRecord(
                        moduleId = moduleId,
                        capability = capability,
                        grantedAtMs = existing?.grantedAtMs ?: clock(),
                        authorizedTargets = existing?.authorizedTargets.orEmpty() + setOfNotNull(target),
                    ),
                )
                if (target != null) {
                    log(moduleId, AuditEventType.TARGET_AUTHORIZED, capability, target, "user confirmed authorization")
                }
                log(moduleId, AuditEventType.CAPABILITY_GRANTED, capability, target, "user consent")
                CapabilityResult.Granted
            }
        }
    }

    override suspend fun isGranted(moduleId: String, capability: Capability, target: String?): Boolean {
        val record = registry.find(moduleId) ?: return false
        if (record.state == ModuleState.INSTALLED || capability !in record.manifest.permissions) return false
        val grant = grants.find(moduleId, capability) ?: return false
        if (!capability.requiresTargetAuthorization) return true
        val normalized = normalizeTarget(target) ?: return false
        return grant.covers(normalized)
    }

    override suspend fun revoke(moduleId: String, capability: Capability) {
        if (grants.find(moduleId, capability) == null) return
        grants.delete(moduleId, capability)
        log(moduleId, AuditEventType.CAPABILITY_REVOKED, capability, null, "revoked by user")
    }

    override suspend fun grantableUpFront(moduleId: String): List<Capability> {
        val record = registry.find(moduleId) ?: return emptyList()
        return record.manifest.permissions.filter { !it.requiresTargetAuthorization && grants.find(moduleId, it) == null }
    }

    override suspend fun grantByUser(moduleId: String, capabilities: Set<Capability>) {
        grantableUpFront(moduleId).filter { it in capabilities }.forEach { capability ->
            grants.save(GrantRecord(moduleId, capability, clock()))
            log(moduleId, AuditEventType.CAPABILITY_GRANTED, capability, null, "user consent before start")
        }
    }

    private fun GrantRecord.covers(target: String?): Boolean =
        !capability.requiresTargetAuthorization || target in authorizedTargets

    private suspend fun deny(
        moduleId: String,
        capability: Capability,
        target: String?,
        reason: DenialReason,
    ): CapabilityResult.Denied {
        log(moduleId, AuditEventType.CAPABILITY_DENIED, capability, target, reason.name)
        return CapabilityResult.Denied(reason)
    }

    private suspend fun log(moduleId: String, type: AuditEventType, capability: Capability, target: String?, detail: String) {
        audit.record(AuditEvent(timestampMs = clock(), moduleId = moduleId, type = type, capability = capability, target = target, detail = detail))
    }

    private fun normalizeTarget(target: String?): String? = target?.trim()?.lowercase()?.ifEmpty { null }
}
