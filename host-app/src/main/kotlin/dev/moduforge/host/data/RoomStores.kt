package dev.moduforge.host.data

import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.GrantRecord
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifests
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Rows that no longer decode (unknown enum constant, invalid manifest) are skipped, never trusted. */
private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    enumValues<E>().firstOrNull { it.name == name }

@Singleton
class RoomModuleRegistry @Inject constructor(private val dao: ModuleDao) : ModuleRegistry {

    override suspend fun find(moduleId: String): ModuleRecord? = dao.find(moduleId)?.toRecord()

    override fun observe(moduleId: String): Flow<ModuleRecord?> = dao.observe(moduleId).map { it?.toRecord() }

    override fun observeAll(): Flow<List<ModuleRecord>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toRecord() } }

    override suspend fun save(record: ModuleRecord) {
        dao.upsert(
            ModuleEntity(
                id = record.id,
                manifestJson = ModuleManifests.encode(record.manifest),
                state = record.state.name,
                installedAtMs = record.installedAtMs,
                signer = record.signer,
                autoStart = record.autoStart,
            ),
        )
    }

    override suspend fun delete(moduleId: String) = dao.delete(moduleId)

    private fun ModuleEntity.toRecord(): ModuleRecord? {
        val manifest = (ModuleManifests.parse(manifestJson) as? ManifestResult.Valid)?.manifest ?: return null
        val state = enumOrNull<ModuleState>(state) ?: return null
        return ModuleRecord(manifest, state, installedAtMs, signer, autoStart)
    }
}

@Singleton
class RoomGrantStore @Inject constructor(private val dao: GrantDao) : GrantStore {

    override suspend fun find(moduleId: String, capability: Capability): GrantRecord? =
        dao.find(moduleId, capability.name)?.toRecord()

    override fun observe(moduleId: String): Flow<List<GrantRecord>> =
        dao.observe(moduleId).map { rows -> rows.mapNotNull { it.toRecord() } }

    override fun observeAll(): Flow<List<GrantRecord>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toRecord() } }

    override suspend fun save(record: GrantRecord) {
        dao.upsert(
            GrantEntity(
                moduleId = record.moduleId,
                capability = record.capability.name,
                grantedAtMs = record.grantedAtMs,
                targets = record.authorizedTargets.joinToString("\n"),
            ),
        )
    }

    override suspend fun delete(moduleId: String, capability: Capability) = dao.delete(moduleId, capability.name)

    override suspend fun deleteAll(moduleId: String) = dao.deleteAll(moduleId)

    private fun GrantEntity.toRecord(): GrantRecord? {
        val capability = enumOrNull<Capability>(capability) ?: return null
        return GrantRecord(moduleId, capability, grantedAtMs, targets.lines().filter { it.isNotEmpty() }.toSet())
    }
}

@Singleton
class RoomAuditLog @Inject constructor(private val dao: AuditDao) : AuditLog {

    override suspend fun record(event: AuditEvent) {
        dao.insert(
            AuditEntity(
                timestampMs = event.timestampMs,
                moduleId = event.moduleId,
                type = event.type.name,
                capability = event.capability?.name,
                target = event.target,
                detail = event.detail,
            ),
        )
    }

    override fun observe(moduleId: String?): Flow<List<AuditEvent>> {
        val rows = if (moduleId == null) dao.observeAll(VISIBLE_EVENTS) else dao.observe(moduleId, VISIBLE_EVENTS)
        return rows.map { list -> list.mapNotNull { it.toEvent() } }
    }

    private fun AuditEntity.toEvent(): AuditEvent? {
        val type = enumOrNull<AuditEventType>(type) ?: return null
        return AuditEvent(id, timestampMs, moduleId, type, enumOrNull<Capability>(capability), target, detail)
    }

    private companion object {
        const val VISIBLE_EVENTS = 1000
    }
}
