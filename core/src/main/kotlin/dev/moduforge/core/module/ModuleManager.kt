package dev.moduforge.core.module

import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuForgeSdk
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.SemVer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface InstallResult {
    data class Installed(val record: ModuleRecord) : InstallResult

    data class Rejected(val problems: List<String>) : InstallResult
}

/**
 * Admission and lifecycle of modules. Owns every state transition and delivers the matching
 * module callback through the [runtime]; a callback that throws or exceeds [callbackTimeoutMs]
 * is audited and never blocks the transition.
 *
 * `onInstall`, `onEnable`, `onDisable` and `onUninstall` run in a short-lived sandbox that is
 * destroyed as soon as the callback returns. Only `onStart` leaves a sandbox alive.
 */
class ModuleManager(
    private val registry: ModuleRegistry,
    private val grants: GrantStore,
    private val audit: AuditLog,
    private val runtime: ModuleRuntime,
    private val hostSdkVersion: SemVer = ModuForgeSdk.version,
    private val callbackTimeoutMs: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Registers a module from its manifest; the module package must already be available to the runtime.
     * A newly installed module is disabled and holds no grants.
     *
     * @param signer hex SHA-256 of the key that signed the package; null for packages bundled with the host.
     */
    suspend fun install(manifestJson: String, signer: String? = null): InstallResult = mutex.withLock {
        val origin = signer?.let { "signed by $it" } ?: ORIGIN_BUNDLED
        val manifest = when (val parsed = ModuleManifests.parse(manifestJson)) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Invalid -> return reject(UNPARSED_MODULE_ID, parsed.problems)
        }
        if (!ModuleManifests.supportsSdk(manifest, hostSdkVersion)) {
            return reject(manifest.id, listOf("requires SDK ${manifest.sdkRange}, host provides $hostSdkVersion"))
        }
        if (manifest.runtime !in runtime.supportedRuntimes) {
            return reject(manifest.id, listOf("runtime '${manifest.runtime.name.lowercase()}' is not supported by this host"))
        }
        if (registry.find(manifest.id) != null) {
            return reject(manifest.id, listOf("module ${manifest.id} is already installed"))
        }
        val record = ModuleRecord(manifest, ModuleState.INSTALLED, clock(), signer)
        registry.save(record)
        log(
            manifest.id,
            AuditEventType.MODULE_INSTALLED,
            "v${manifest.version}; $origin; declares ${manifest.permissions.joinToString().ifEmpty { "nothing" }}",
        )
        runTransient(record, LifecyclePhase.INSTALL)
        InstallResult.Installed(record)
    }

    /**
     * Replaces an installed module with another version of itself. Allowed only for a module that
     * is not running, from the same origin — the same signing key, or unsigned code replacing an
     * unsigned module written on this device — and with a version that is not older.
     * State and grants are kept; grants for capabilities the new manifest dropped are removed.
     *
     * @param signer key of the new package; null for unsigned code written on this device.
     * @param replacePackage swaps the stored package; called once every check has passed.
     */
    suspend fun update(manifestJson: String, signer: String?, replacePackage: suspend () -> Boolean): InstallResult = mutex.withLock {
        val manifest = when (val parsed = ModuleManifests.parse(manifestJson)) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Invalid -> return reject(UNPARSED_MODULE_ID, parsed.problems)
        }
        val existing = registry.find(manifest.id)
            ?: return reject(manifest.id, listOf("module ${manifest.id} is not installed"))
        val problem = when {
            existing.signer == null && signer != null -> "a module written on this device cannot be replaced by a package"
            existing.signer != null && signer == null -> "a signed module cannot be replaced by unsigned code"
            existing.signer != signer -> "the package is signed by a different key than the installed module"
            existing.state == ModuleState.RUNNING -> "stop the module before updating it"
            !ModuleManifests.supportsSdk(manifest, hostSdkVersion) -> "requires SDK ${manifest.sdkRange}, host provides $hostSdkVersion"
            manifest.runtime !in runtime.supportedRuntimes -> "runtime '${manifest.runtime.name.lowercase()}' is not supported by this host"
            SemVer.parse(manifest.version) < SemVer.parse(existing.manifest.version) ->
                "version ${manifest.version} is older than the installed ${existing.manifest.version}"
            else -> null
        }
        if (problem != null) return reject(manifest.id, listOf(problem))
        if (!replacePackage()) return reject(manifest.id, listOf("module package could not be stored"))

        val record = existing.copy(manifest = manifest)
        registry.save(record)
        (existing.manifest.permissions - manifest.permissions.toSet()).forEach { grants.delete(manifest.id, it) }
        log(manifest.id, AuditEventType.MODULE_UPDATED, "${existing.manifest.version} -> ${manifest.version}")
        InstallResult.Installed(record)
    }

    /** @return false when the module is unknown. */
    suspend fun setAutoStart(moduleId: String, enabled: Boolean): Boolean = mutex.withLock {
        val record = registry.find(moduleId) ?: return false
        if (record.autoStart != enabled) registry.save(record.copy(autoStart = enabled))
        true
    }

    /**
     * Starts every enabled module the user marked for automatic start, provided it may run without
     * the host UI (holds BACKGROUND_EXECUTION). Called when the host process starts.
     */
    suspend fun startAutoStartModules() {
        registry.observeAll().first()
            .filter { it.autoStart && it.state == ModuleState.ENABLED }
            .filter { grants.find(it.id, Capability.BACKGROUND_EXECUTION) != null }
            .forEach { start(it.id) }
    }

    /** @return false when the module is unknown or is running. */
    suspend fun setEnabled(moduleId: String, enabled: Boolean): Boolean = mutex.withLock {
        val record = registry.find(moduleId) ?: return false
        val next = if (enabled) ModuleState.ENABLED else ModuleState.INSTALLED
        if (record.state == next) return true
        if (!record.state.canTransitionTo(next)) return false
        if (enabled) {
            registry.save(record.copy(state = next))
            log(moduleId, AuditEventType.MODULE_ENABLED)
            runTransient(record, LifecyclePhase.ENABLE)
        } else {
            runTransient(record, LifecyclePhase.DISABLE)
            registry.save(record.copy(state = next))
            log(moduleId, AuditEventType.MODULE_DISABLED)
        }
        true
    }

    /** Launches an enabled module. @return false when it is not enabled or failed to start. */
    suspend fun start(moduleId: String): Boolean = mutex.withLock {
        val record = registry.find(moduleId) ?: return false
        if (record.state != ModuleState.ENABLED) return false
        val failure = launchAndInvoke(record, LifecyclePhase.START)
        if (failure != null) {
            runtime.kill(moduleId)
            log(moduleId, AuditEventType.MODULE_CALLBACK_FAILED, "START: ${failure.reason}")
            return false
        }
        registry.save(record.copy(state = ModuleState.RUNNING))
        log(moduleId, AuditEventType.MODULE_STARTED)
        true
    }

    /**
     * Delivers `onStop`, then destroys the sandbox whether or not the callback completed.
     *
     * @param reason why the module is stopped when it was not the user's request; recorded in the audit log.
     */
    suspend fun stop(moduleId: String, reason: String = ""): Boolean = mutex.withLock {
        val record = registry.find(moduleId) ?: return false
        if (record.state != ModuleState.RUNNING) return false
        val result = bounded { runtime.invoke(moduleId, LifecyclePhase.STOP) }
        runtime.kill(moduleId)
        registry.save(record.copy(state = ModuleState.ENABLED))
        if (result is RuntimeResult.Failed) {
            log(moduleId, AuditEventType.MODULE_CALLBACK_FAILED, "STOP: ${result.reason}")
        }
        log(moduleId, AuditEventType.MODULE_STOPPED, reason)
        true
    }

    /**
     * Destroys the sandbox of a module without notifying it. The sandbox goes away immediately,
     * even while another operation is waiting for the module; that operation then fails.
     */
    suspend fun kill(moduleId: String): Boolean {
        runtime.kill(moduleId)
        return mutex.withLock {
            val record = registry.find(moduleId) ?: return false
            if (record.state != ModuleState.RUNNING) return false
            registry.save(record.copy(state = ModuleState.ENABLED))
            log(moduleId, AuditEventType.MODULE_KILLED, "forced by user")
            true
        }
    }

    /** Records that the sandbox of a running module died on its own. */
    suspend fun onUnexpectedExit(moduleId: String): Unit = mutex.withLock {
        val record = registry.find(moduleId) ?: return
        if (record.state != ModuleState.RUNNING || runtime.isAlive(moduleId)) return
        registry.save(record.copy(state = ModuleState.ENABLED))
        log(moduleId, AuditEventType.MODULE_CRASHED, "sandbox process died")
    }

    /** Reconciles persisted state with reality after a host restart: no sandbox survives the host. */
    suspend fun recover(): Unit = mutex.withLock {
        registry.observeAll().first()
            .filter { it.state == ModuleState.RUNNING && !runtime.isAlive(it.id) }
            .forEach { record ->
                registry.save(record.copy(state = ModuleState.ENABLED))
                log(record.id, AuditEventType.MODULE_CRASHED, "host restarted")
            }
    }

    /** Removes a disabled module together with all its grants. @return false when unknown or not disabled. */
    suspend fun uninstall(moduleId: String): Boolean = mutex.withLock {
        val record = registry.find(moduleId) ?: return false
        if (record.state != ModuleState.INSTALLED) return false
        runTransient(record, LifecyclePhase.UNINSTALL)
        grants.deleteAll(moduleId)
        registry.delete(moduleId)
        log(moduleId, AuditEventType.MODULE_UNINSTALLED)
        true
    }

    /** Records a package that was refused before it reached [install], e.g. for a bad signature. */
    suspend fun reportRejected(moduleId: String?, reason: String) {
        log(moduleId ?: UNPARSED_MODULE_ID, AuditEventType.MODULE_REJECTED, reason)
    }

    private suspend fun runTransient(record: ModuleRecord, phase: LifecyclePhase) {
        val failure = launchAndInvoke(record, phase)
        runtime.kill(record.id)
        if (failure != null) log(record.id, AuditEventType.MODULE_CALLBACK_FAILED, "$phase: ${failure.reason}")
    }

    private suspend fun launchAndInvoke(record: ModuleRecord, phase: LifecyclePhase): RuntimeResult.Failed? {
        val launched = bounded { runtime.launch(record) }
        if (launched is RuntimeResult.Failed) return launched
        return bounded { runtime.invoke(record.id, phase) } as? RuntimeResult.Failed
    }

    private suspend fun bounded(block: suspend () -> RuntimeResult): RuntimeResult =
        withTimeoutOrNull(callbackTimeoutMs) { block() }
            ?: RuntimeResult.Failed("no answer within $callbackTimeoutMs ms")

    private suspend fun reject(moduleId: String, problems: List<String>): InstallResult.Rejected {
        log(moduleId, AuditEventType.MODULE_REJECTED, problems.joinToString("; "))
        return InstallResult.Rejected(problems)
    }

    private suspend fun log(moduleId: String, type: AuditEventType, detail: String = "") {
        audit.record(AuditEvent(timestampMs = clock(), moduleId = moduleId, type = type, detail = detail))
    }

    companion object {
        /** Audit placeholder for packages whose manifest could not be read. */
        const val UNPARSED_MODULE_ID = "(unparsed)"

        const val ORIGIN_BUNDLED = "unsigned, written on this device"
    }
}
