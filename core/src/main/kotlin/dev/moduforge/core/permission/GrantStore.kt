package dev.moduforge.core.permission

import dev.moduforge.sdk.Capability
import kotlinx.coroutines.flow.Flow

/**
 * Persisted user consent for one capability of one module.
 *
 * @property authorizedTargets targets the user confirmed being authorized to act against;
 * only meaningful for capabilities with [Capability.requiresTargetAuthorization].
 */
data class GrantRecord(
    val moduleId: String,
    val capability: Capability,
    val grantedAtMs: Long,
    val authorizedTargets: Set<String> = emptySet(),
)

interface GrantStore {
    suspend fun find(moduleId: String, capability: Capability): GrantRecord?

    fun observe(moduleId: String): Flow<List<GrantRecord>>

    /** Grants of all modules. */
    fun observeAll(): Flow<List<GrantRecord>>

    /** Inserts or replaces the grant for the same module and capability. */
    suspend fun save(record: GrantRecord)

    suspend fun delete(moduleId: String, capability: Capability)

    suspend fun deleteAll(moduleId: String)
}
