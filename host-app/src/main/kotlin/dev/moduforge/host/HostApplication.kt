package dev.moduforge.host

import android.app.Application
import android.os.Build
import android.os.Process
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRuntime
import dev.moduforge.host.runtime.BackgroundCoordinator
import dev.moduforge.host.runtime.DeveloperMode
import dev.moduforge.sandbox.ModulePackageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@HiltAndroidApp
class HostApplication : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface HostEntryPoint {
        fun moduleManager(): ModuleManager

        fun moduleRuntime(): ModuleRuntime

        fun applicationScope(): CoroutineScope

        fun backgroundCoordinator(): BackgroundCoordinator

        fun packageStore(): ModulePackageStore

        fun developerMode(): DeveloperMode
    }

    /** Recovery and automatic module start, running since process start; null in sandbox processes. */
    var startup: Job? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // The same Application class is instantiated in sandbox processes; host services must not start there.
        if (isSandboxProcess()) return

        val host = EntryPointAccessors.fromApplication(this, HostEntryPoint::class.java)
        host.backgroundCoordinator().start()
        host.developerMode().start()
        startup = host.applicationScope().launch {
            host.packageStore().clearStaging()
            host.moduleManager().recover()
            host.moduleManager().startAutoStartModules()
        }
        host.applicationScope().launch {
            host.moduleRuntime().stopRequests.collect { request ->
                host.moduleManager().stop(request.moduleId, request.reason)
            }
        }
        host.applicationScope().launch {
            host.moduleRuntime().unexpectedExits.collect { moduleId ->
                host.moduleManager().onUnexpectedExit(moduleId)
            }
        }
    }

    private fun isSandboxProcess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Process.isIsolated()
        } else {
            // Isolated UIDs occupy the app-id range 99000..99999.
            Process.myUid() % 100_000 in 99_000..99_999
        }
}
