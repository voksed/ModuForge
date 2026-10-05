package dev.moduforge.modules.probe

import android.content.ClipboardManager
import android.content.Context
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Declares no permissions and tries to reach the network, the host's files and the
 * clipboard directly, bypassing the SDK. Each attempt is reported as one log line
 * `PROBE <name>=BLOCKED|LEAKED (<detail>)`; `PROBE done` ends the report.
 */
class SandboxProbeModule : Module {

    private var work: CoroutineScope? = null

    override suspend fun onStart(context: ModuleContext) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { work = it }
        scope.launch {
            attempt(context, "network") {
                Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), TIMEOUT_MS) }
                "connected"
            }
            attempt(context, "dns") {
                java.net.InetAddress.getByName("example.com").hostAddress.orEmpty()
            }
            attempt(context, "host-file-read") {
                "read ${File("$HOST_DATA/databases/host.db").readBytes().size} bytes"
            }
            attempt(context, "host-file-write") {
                File("$HOST_DATA/files/escape.txt").writeText("escaped")
                "written"
            }
            attempt(context, "host-dir-list") {
                val names = File(HOST_DATA).list() ?: error("not listable")
                "listed ${names.size} entries"
            }
            attempt(context, "shared-storage") {
                val names = File("/sdcard").list() ?: error("not listable")
                "listed ${names.size} entries"
            }
            attempt(context, "clipboard-read") {
                val clip = clipboard().primaryClip ?: error("no clip returned")
                "read ${clip.itemCount} items"
            }
            attempt(context, "window-service") {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.WINDOW_SERVICE) ?: error("service not visible")
                "found $binder"
            }
            val undeclared = context.capabilities.request(
                CapabilityRequest(Capability.NETWORK_OUTBOUND, "Probe: requesting a capability the manifest does not declare."),
            )
            context.log.info("PROBE undeclared-capability=$undeclared")
            context.log.info("PROBE done")
        }
    }

    override suspend fun onStop(context: ModuleContext) {
        work?.cancel()
        work = null
    }

    private fun attempt(context: ModuleContext, name: String, action: () -> String) {
        val outcome = try {
            "LEAKED (${action()})"
        } catch (t: Throwable) {
            "BLOCKED (${t.javaClass.simpleName}: ${t.message?.take(120)})"
        }
        context.log.info("PROBE $name=$outcome")
    }

    /** Reaches for the process-wide application object, which the SDK never hands to a module. */
    private fun clipboard(): ClipboardManager {
        val application = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context ?: error("no application object")
        return application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private companion object {
        const val TIMEOUT_MS = 3_000
        const val HOST_DATA = "/data/data/dev.moduforge.host"
    }
}
