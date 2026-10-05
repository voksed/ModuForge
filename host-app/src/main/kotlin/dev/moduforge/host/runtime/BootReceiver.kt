package dev.moduforge.host.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.moduforge.host.HostApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings the host up after the device boots. Starting the process is what matters: the
 * application start-up then launches the modules the user marked for automatic start.
 * The broadcast is held until that has happened so the process is not reclaimed in between.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val startup = (context.applicationContext as HostApplication).startup ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(HOLD_MS) { startup.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Below the ten seconds a receiver may hold a broadcast. */
        const val HOLD_MS = 8_000L
    }
}
