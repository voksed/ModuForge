package dev.moduforge.host.runtime

import dev.moduforge.core.module.ModuleLogSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class ModuleLogLine(val timestampMs: Long, val level: ModuleLogSink.Level, val message: String)

/** Recent diagnostic output of modules, kept in memory only and bounded per module. */
@Singleton
class ModuleLogs @Inject constructor() : ModuleLogSink {

    private val lines = MutableStateFlow<Map<String, List<ModuleLogLine>>>(emptyMap())

    override fun append(moduleId: String, level: ModuleLogSink.Level, message: String) {
        val line = ModuleLogLine(System.currentTimeMillis(), level, message)
        lines.update { all -> all + (moduleId to (all[moduleId].orEmpty() + line).takeLast(MAX_LINES)) }
    }

    /** Oldest first. */
    fun observe(moduleId: String): Flow<List<ModuleLogLine>> =
        lines.map { it[moduleId].orEmpty() }.distinctUntilChanged()

    fun clear(moduleId: String) {
        lines.update { it - moduleId }
    }

    private companion object {
        const val MAX_LINES = 200
    }
}
