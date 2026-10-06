package dev.moduforge.host.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.module.ModuleLogSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class ModuleLogLine(val timestampMs: Long, val level: ModuleLogSink.Level, val message: String)

/**
 * Output of modules. The most recent lines of every module are kept in a file of its own, so
 * they survive a restart of the host and can be exported; older lines are dropped.
 */
@Singleton
class ModuleLogs @Inject constructor(@ApplicationContext context: Context) : ModuleLogSink {

    private val directory = File(context.filesDir, "module-logs")
    private val lines = MutableStateFlow<Map<String, List<ModuleLogLine>>>(emptyMap())

    /** All file access and every change of [lines] happen on this thread, in order. */
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "module-logs").apply { isDaemon = true } }
    private val loaded = mutableSetOf<String>()
    private val storedCount = mutableMapOf<String, Int>()

    override fun append(moduleId: String, level: ModuleLogSink.Level, message: String) {
        val line = ModuleLogLine(System.currentTimeMillis(), level, message)
        worker.execute {
            load(moduleId)
            val kept = (lines.value[moduleId].orEmpty() + line).takeLast(MAX_LINES)
            lines.update { it + (moduleId to kept) }
            try {
                val stored = (storedCount[moduleId] ?: 0) + 1
                if (stored > MAX_LINES * 2) {
                    // The file has grown to twice what is kept: rewrite it with the kept lines only.
                    file(moduleId).writeText(kept.joinToString("") { encode(it) })
                    storedCount[moduleId] = kept.size
                } else {
                    file(moduleId).appendText(encode(line))
                    storedCount[moduleId] = stored
                }
            } catch (e: IOException) {
                // Output stays available in memory.
            }
        }
    }

    /** Oldest first. Lines stored by an earlier run of the host are included. */
    fun observe(moduleId: String): Flow<List<ModuleLogLine>> {
        worker.execute { load(moduleId) }
        return lines.map { it[moduleId].orEmpty() }.distinctUntilChanged()
    }

    /** The kept output as text, one line per entry with its time and level. */
    fun export(moduleId: String): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return lines.value[moduleId].orEmpty().joinToString("\n") { line ->
            val level = if (line.level == ModuleLogSink.Level.INFO) "" else " ${line.level.name}"
            "${format.format(Date(line.timestampMs))}$level  ${line.message}"
        }
    }

    fun clear(moduleId: String) {
        worker.execute {
            loaded += moduleId
            storedCount[moduleId] = 0
            lines.update { it - moduleId }
            file(moduleId).delete()
        }
    }

    private fun load(moduleId: String) {
        if (!loaded.add(moduleId)) return
        val stored = try {
            file(moduleId).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull(::decode)
        } catch (e: IOException) {
            emptyList()
        }
        storedCount[moduleId] = stored.size
        if (stored.isNotEmpty()) lines.update { it + (moduleId to stored.takeLast(MAX_LINES)) }
    }

    private fun file(moduleId: String): File = File(directory.apply { mkdirs() }, "$moduleId.log")

    private fun encode(line: ModuleLogLine): String =
        "${line.timestampMs}\t${line.level.name}\t${line.message.replace("\\", "\\\\").replace("\n", "\\n")}\n"

    private fun decode(text: String): ModuleLogLine? {
        val parts = text.split('\t', limit = 3)
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val level = ModuleLogSink.Level.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return null
        val message = StringBuilder()
        val raw = parts.getOrNull(2).orEmpty()
        var index = 0
        while (index < raw.length) {
            val char = raw[index++]
            if (char == '\\' && index < raw.length) {
                message.append(if (raw[index] == 'n') '\n' else raw[index])
                index++
            } else {
                message.append(char)
            }
        }
        return ModuleLogLine(time, level, message.toString())
    }

    private companion object {
        const val MAX_LINES = 500
    }
}
