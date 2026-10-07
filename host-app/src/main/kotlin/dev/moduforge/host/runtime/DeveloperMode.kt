package dev.moduforge.host.runtime

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.dev.DevPush
import dev.moduforge.core.dev.DevPushServer
import dev.moduforge.core.dev.PushReply
import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.sandbox.ModuleInstaller
import dev.moduforge.sandbox.PackageInspection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @property enabled packages pushed from a computer are accepted.
 * @property wifi connections from the local network are accepted, not only those forwarded over USB.
 * @property token what the computer has to know; shown to the user, never sent over the connection.
 * @property listening the connection point is open; false while enabled means the port is taken.
 */
data class DeveloperState(val enabled: Boolean, val wifi: Boolean, val token: String, val listening: Boolean = false)

/**
 * Developer mode: lets the module author's computer install a package and restart its module
 * with one command (`mfrg push`), and read the module's output there.
 *
 * Off until the user turns it on. A pushed package goes through the same verification as one
 * picked in the app, the same signer rules and the same audit log; permissions are still
 * granted only on this device. The connection point exists while the app's process lives.
 */
@Singleton
class DeveloperMode @Inject constructor(
    @ApplicationContext context: Context,
    private val installer: ModuleInstaller,
    private val manager: ModuleManager,
    private val registry: ModuleRegistry,
    private val broker: PermissionBroker,
    private val logs: ModuleLogs,
    private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences("developer", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        DeveloperState(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            wifi = preferences.getBoolean(KEY_WIFI, false),
            token = preferences.getString(KEY_TOKEN, null) ?: DevPush.newToken().also { preferences.edit { putString(KEY_TOKEN, it) } },
        ),
    )
    val state: StateFlow<DeveloperState> = _state.asStateFlow()

    private var server: DevPushServer? = null

    /** When the latest push of a module began; its output is followed from that moment. */
    private val pushedAt = ConcurrentHashMap<String, Long>()

    fun setEnabled(enabled: Boolean) = change({ putBoolean(KEY_ENABLED, enabled) }) { it.copy(enabled = enabled) }

    fun setWifi(wifi: Boolean) = change({ putBoolean(KEY_WIFI, wifi) }) { it.copy(wifi = wifi) }

    /** Replaces the token; computers paired with the old one are locked out. */
    fun renewToken() {
        val token = DevPush.newToken()
        change({ putString(KEY_TOKEN, token) }) { it.copy(token = token) }
    }

    /** Opens the connection point when the mode is on. Called once at process start. */
    fun start() = restart()

    /** Addresses of this device in the local network, for `mfrg push --host`. */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .toList()
    } catch (e: IOException) {
        emptyList()
    }

    private fun change(store: android.content.SharedPreferences.Editor.() -> Unit, apply: (DeveloperState) -> DeveloperState) {
        preferences.edit(action = store)
        _state.update(apply)
        restart()
    }

    private fun restart() {
        scope.launch(Dispatchers.IO) {
            synchronized(this@DeveloperMode) {
                server?.close()
                server = null
                val wanted = _state.value
                val listening = wanted.enabled && try {
                    server = DevPushServer({ _state.value.token }, handler).also { it.start(DevPush.DEFAULT_PORT, wanted.wifi) }
                    true
                } catch (e: IOException) {
                    server = null
                    false
                }
                _state.update { it.copy(listening = listening) }
            }
        }
    }

    private val handler = object : DevPushServer.Handler {
        override fun install(packageBytes: ByteArray): PushReply = runBlocking { installPushed(packageBytes) }

        override fun output(moduleId: String): Flow<String> = outputSince(moduleId, pushedAt[moduleId] ?: System.currentTimeMillis())
    }

    private suspend fun installPushed(packageBytes: ByteArray): PushReply = withContext(Dispatchers.Default) {
        val inspected = when (val inspection = installer.inspect { packageBytes.inputStream() }) {
            is PackageInspection.Rejected -> return@withContext PushReply(false, inspection.reason)
            is PackageInspection.Ready -> inspection
        }
        val id = inspected.manifest.id
        pushedAt[id] = System.currentTimeMillis()
        // A running module cannot be replaced; it is stopped for the update and started again.
        val wasRunning = registry.find(id)?.state == ModuleState.RUNNING
        if (wasRunning) manager.stop(id, "developer push")

        when (val result = installer.install(inspected)) {
            is InstallResult.Rejected -> {
                if (wasRunning) manager.start(id)
                PushReply(false, result.problems.joinToString("; "))
            }
            is InstallResult.Installed -> {
                if (registry.find(id)?.state == ModuleState.INSTALLED) manager.setEnabled(id, true)
                val name = "${inspected.manifest.name} ${inspected.manifest.version}"
                // Permissions are granted on the phone only: the first push stops here, later ones keep the grants.
                val missing = broker.grantableUpFront(id)
                if (missing.isNotEmpty()) {
                    return@withContext PushReply(
                        true,
                        "$name installed, not started: it needs ${missing.joinToString { it.name }}. " +
                            "Open it in the app and press Start once to allow that; the next push starts it by itself.",
                        id,
                    )
                }
                val outcome = if (manager.start(id)) "started" else "could not be started, see its screen in the app"
                PushReply(true, "$name installed, $outcome", id)
            }
        }
    }

    /** Lines of the module's output not older than [since], each exactly once. */
    private fun outputSince(moduleId: String, since: Long): Flow<String> = flow {
        var lastTime = since
        var sentAtLastTime = 0
        logs.observe(moduleId).collect { lines ->
            // The log keeps a bounded tail, so progress is tracked by time rather than by position.
            var skip = sentAtLastTime
            for (line in lines) {
                if (line.timestampMs < lastTime) continue
                if (line.timestampMs == lastTime && skip > 0) {
                    skip--
                    continue
                }
                emit(format(line))
                if (line.timestampMs == lastTime) {
                    sentAtLastTime++
                } else {
                    lastTime = line.timestampMs
                    sentAtLastTime = 1
                }
            }
        }
    }

    private fun format(line: ModuleLogLine): String = when (line.level) {
        ModuleLogSink.Level.INFO -> line.message
        ModuleLogSink.Level.WARN -> "warning: ${line.message}"
        ModuleLogSink.Level.ERROR -> "error: ${line.message}"
    } + "\n"

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_WIFI = "wifi"
        const val KEY_TOKEN = "token"
    }
}
