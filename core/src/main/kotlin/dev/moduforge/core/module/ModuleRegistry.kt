package dev.moduforge.core.module

import dev.moduforge.sdk.ModuleManifest
import kotlinx.coroutines.flow.Flow

/**
 * Host-side lifecycle state. Allowed moves mirror the module callbacks:
 * `INSTALLED ⇄ ENABLED ⇄ RUNNING`; uninstallation is only possible from [INSTALLED].
 */
enum class ModuleState {
    /** Present on the host, not allowed to run or to request capabilities. */
    INSTALLED,

    /** Allowed to run; no process is alive. */
    ENABLED,

    /** A sandbox process is alive. */
    RUNNING,
    ;

    fun canTransitionTo(next: ModuleState): Boolean = when (this) {
        INSTALLED -> next == ENABLED
        ENABLED -> next == RUNNING || next == INSTALLED
        RUNNING -> next == ENABLED
    }
}

/**
 * @property signer hex SHA-256 of the key the package was signed with; null for packages bundled with the host.
 * @property autoStart the user asked for the module to be started whenever the host starts.
 */
data class ModuleRecord(
    val manifest: ModuleManifest,
    val state: ModuleState,
    val installedAtMs: Long,
    val signer: String? = null,
    val autoStart: Boolean = false,
) {
    val id: String get() = manifest.id
}

/** Persistent catalogue of installed modules. */
interface ModuleRegistry {
    suspend fun find(moduleId: String): ModuleRecord?

    fun observe(moduleId: String): Flow<ModuleRecord?>

    fun observeAll(): Flow<List<ModuleRecord>>

    /** Inserts or replaces the record with the same module id. */
    suspend fun save(record: ModuleRecord)

    suspend fun delete(moduleId: String)
}
