package dev.moduforge.host.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.module.ModuleState
import dev.moduforge.core.permission.GrantStore
import dev.moduforge.host.MainActivity
import dev.moduforge.host.R
import dev.moduforge.sdk.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which modules outlive the host UI. A running module that holds `BACKGROUND_EXECUTION`
 * keeps the host alive through a foreground service; a running module without it is stopped
 * when the host leaves the screen.
 */
@Singleton
class BackgroundCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ModuleRegistry,
    private val grants: GrantStore,
    private val manager: ModuleManager,
    private val scope: CoroutineScope,
) {
    private val _backgroundModules = MutableStateFlow(0)

    /** Number of running modules allowed to work in the background. */
    val backgroundModules: StateFlow<Int> = _backgroundModules

    private val _wantsNotifications = MutableStateFlow(false)

    /** True once something the user allowed needs the system notification permission to be visible. */
    val wantsNotifications: StateFlow<Boolean> = _wantsNotifications

    /** Starts observing. Must be called once, on the main thread, in the host process. */
    fun start() {
        scope.launch {
            combine(registry.observeAll(), grants.observeAll()) { modules, granted ->
                val allowed = granted.filter { it.capability == Capability.BACKGROUND_EXECUTION }.map { it.moduleId }.toSet()
                modules.count { it.state == ModuleState.RUNNING && it.id in allowed && Capability.BACKGROUND_EXECUTION in it.manifest.permissions }
            }.distinctUntilChanged().collect { count ->
                _backgroundModules.value = count
                withContext(Dispatchers.Main) { ModuleHostService.update(context, count) }
            }
        }
        scope.launch {
            combine(backgroundModules, grants.observeAll()) { background, granted ->
                background > 0 || granted.any { it.capability == Capability.NOTIFICATIONS }
            }.collect { _wantsNotifications.value = it }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    scope.launch { stopForegroundOnlyModules() }
                }
            },
        )
    }

    private suspend fun stopForegroundOnlyModules() {
        val allowed = grants.observeAll().first()
            .filter { it.capability == Capability.BACKGROUND_EXECUTION }.map { it.moduleId }.toSet()
        registry.observeAll().first()
            .filter { it.state == ModuleState.RUNNING && it.id !in allowed }
            .forEach { manager.stop(it.id, context.getString(R.string.background_stopped_detail)) }
    }
}

/** Foreground service whose only job is to keep the host process, and with it the sandboxes, alive. */
class ModuleHostService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification(intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Not sticky: after the host process dies no sandbox is left to keep alive.
        return START_NOT_STICKY
    }

    private fun notification(count: Int): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(resources.getQuantityString(R.plurals.background_title, count, count))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "running-modules"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"

        /** Runs the service while [count] is positive. */
        fun update(context: Context, count: Int) {
            val intent = Intent(context, ModuleHostService::class.java).putExtra(EXTRA_COUNT, count)
            if (count <= 0) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // The host is not on screen, so the system refuses a new foreground service.
                // Modules keep running for as long as the system leaves the host process alone.
            }
        }
    }
}
