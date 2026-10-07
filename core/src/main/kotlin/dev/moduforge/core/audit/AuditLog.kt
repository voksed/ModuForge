package dev.moduforge.core.audit

import dev.moduforge.sdk.Capability
import kotlinx.coroutines.flow.Flow

enum class AuditEventType {
    MODULE_INSTALLED,
    MODULE_REJECTED,
    MODULE_UPDATED,
    MODULE_ENABLED,
    MODULE_DISABLED,
    MODULE_STARTED,
    MODULE_STOPPED,
    MODULE_KILLED,
    MODULE_CRASHED,
    MODULE_CALLBACK_FAILED,
    MODULE_UNINSTALLED,
    CAPABILITY_REQUESTED,
    CAPABILITY_GRANTED,
    CAPABILITY_DENIED,
    CAPABILITY_REVOKED,
    TARGET_AUTHORIZED,
    NETWORK_CONNECTED,
    NETWORK_REFUSED,

    /** A module used the camera, the screen or the apps of the device. */
    DEVICE_CALL,
}

/**
 * Single entry of the audit trail.
 *
 * @property id storage-assigned identifier; 0 for events not yet persisted.
 * @property target host, address or CIDR the event refers to, when applicable.
 */
data class AuditEvent(
    val id: Long = 0,
    val timestampMs: Long,
    val moduleId: String,
    val type: AuditEventType,
    val capability: Capability? = null,
    val target: String? = null,
    val detail: String = "",
)

/** Append-only record of everything modules requested, received and did. */
interface AuditLog {
    suspend fun record(event: AuditEvent)

    /** Newest first. A null [moduleId] selects events of all modules. */
    fun observe(moduleId: String? = null): Flow<List<AuditEvent>>
}
