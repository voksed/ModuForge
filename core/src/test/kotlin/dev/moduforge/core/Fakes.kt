package dev.moduforge.core

import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.module.LifecyclePhase
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleRuntime
import dev.moduforge.core.module.RuntimeResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import dev.moduforge.core.permission.ConsentDecision
import dev.moduforge.core.permission.ConsentPrompt
import dev.moduforge.core.permission.ConsentPrompter
import dev.moduforge.core.permission.GrantRecord
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryAuditLog : AuditLog {
    private val state = MutableStateFlow<List<AuditEvent>>(emptyList())

    val events: List<AuditEvent> get() = state.value

    override suspend fun record(event: AuditEvent) {
        state.value = state.value + event.copy(id = state.value.size + 1L)
    }

    override fun observe(moduleId: String?): Flow<List<AuditEvent>> =
        state.map { all -> all.filter { moduleId == null || it.moduleId == moduleId }.reversed() }
}

class InMemoryModuleRegistry : ModuleRegistry {
    private val state = MutableStateFlow<Map<String, ModuleRecord>>(emptyMap())

    override suspend fun find(moduleId: String): ModuleRecord? = state.value[moduleId]

    override fun observe(moduleId: String): Flow<ModuleRecord?> = state.map { it[moduleId] }

    override fun observeAll(): Flow<List<ModuleRecord>> = state.map { it.values.toList() }

    override suspend fun save(record: ModuleRecord) {
        state.value = state.value + (record.id to record)
    }

    override suspend fun delete(moduleId: String) {
        state.value = state.value - moduleId
    }
}

class InMemoryGrantStore : GrantStore {
    private val state = MutableStateFlow<Map<Pair<String, Capability>, GrantRecord>>(emptyMap())

    val all: Collection<GrantRecord> get() = state.value.values

    override suspend fun find(moduleId: String, capability: Capability): GrantRecord? =
        state.value[moduleId to capability]

    override fun observe(moduleId: String): Flow<List<GrantRecord>> =
        state.map { all -> all.values.filter { it.moduleId == moduleId } }

    override fun observeAll(): Flow<List<GrantRecord>> = state.map { it.values.toList() }

    override suspend fun save(record: GrantRecord) {
        state.value = state.value + ((record.moduleId to record.capability) to record)
    }

    override suspend fun delete(moduleId: String, capability: Capability) {
        state.value = state.value - (moduleId to capability)
    }

    override suspend fun deleteAll(moduleId: String) {
        state.value = state.value.filterKeys { it.first != moduleId }
    }
}

/** Scripted runtime: records calls, fails or hangs the phases it is told to. */
class FakeRuntime : ModuleRuntime {
    val calls = mutableListOf<String>()
    val alive = mutableSetOf<String>()
    var launchFailure: String? = null
    val failingPhases = mutableSetOf<LifecyclePhase>()
    val hangingPhases = mutableSetOf<LifecyclePhase>()
    override val unexpectedExits = MutableSharedFlow<String>()
    override val stopRequests = MutableSharedFlow<dev.moduforge.core.module.StopRequest>()
    override val supportedRuntimes = setOf(dev.moduforge.sdk.ModuleRuntimeKind.DEX)

    override suspend fun launch(record: ModuleRecord): RuntimeResult {
        calls += "launch"
        launchFailure?.let { return RuntimeResult.Failed(it) }
        alive += record.id
        return RuntimeResult.Ok
    }

    override suspend fun invoke(moduleId: String, phase: LifecyclePhase): RuntimeResult {
        calls += phase.name
        if (phase in hangingPhases) awaitCancellation()
        return if (phase in failingPhases) RuntimeResult.Failed("boom") else RuntimeResult.Ok
    }

    override fun kill(moduleId: String) {
        calls += "kill"
        alive -= moduleId
    }

    override fun isAlive(moduleId: String): Boolean = moduleId in alive

    override fun sendUiEvent(moduleId: String, event: dev.moduforge.sdk.ui.UiEvent) {
        calls += "ui:${event.id}"
    }
}

/** Answers every prompt with [decision] and remembers what was asked. */
class RecordingPrompter(var decision: ConsentDecision = ConsentDecision.Deny) : ConsentPrompter {
    val prompts = mutableListOf<ConsentPrompt>()

    override suspend fun requestConsent(prompt: ConsentPrompt): ConsentDecision {
        prompts += prompt
        return decision
    }
}

fun manifest(
    id: String = "com.example.tool",
    permissions: List<Capability> = emptyList(),
    sdkRange: String = ">=1.0.0 <2.0.0",
) = ModuleManifest(
    id = id,
    name = "Tool",
    version = "1.0.0",
    sdkRange = sdkRange,
    entry = "com.example.tool.ToolModule",
    permissions = permissions,
)
