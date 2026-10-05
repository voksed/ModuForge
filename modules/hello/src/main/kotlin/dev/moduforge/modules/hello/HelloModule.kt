package dev.moduforge.modules.hello

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.TextStyle
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.column
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal module walking through the whole lifecycle with a small UI.
 * Lifecycle callbacks return promptly; anything that may wait for the user
 * runs in [work], which ends in [onStop].
 */
class HelloModule : Module {

    private var work: CoroutineScope? = null
    private var clicks = 0
    private var name = ""
    private var permission = "not requested"

    override suspend fun onInstall(context: ModuleContext) = context.log.info("onInstall")

    override suspend fun onEnable(context: ModuleContext) = context.log.info("onEnable")

    override suspend fun onStart(context: ModuleContext) {
        context.log.info("onStart: hello from ${context.manifest.name} ${context.manifest.version}")
        work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        render(context)
    }

    override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
        when (event) {
            is UiEvent.Click -> when (event.id) {
                ID_COUNT -> clicks++
                ID_PERMISSION -> requestPermission(context)
            }
            is UiEvent.TextChanged -> if (event.id == ID_NAME) name = event.value
        }
        render(context)
    }

    override suspend fun onStop(context: ModuleContext) {
        work?.cancel()
        work = null
        context.log.info("onStop after $clicks clicks")
    }

    override suspend fun onDisable(context: ModuleContext) = context.log.info("onDisable")

    override suspend fun onUninstall(context: ModuleContext) = context.log.info("onUninstall")

    private fun requestPermission(context: ModuleContext) {
        permission = "waiting for your answer"
        work?.launch {
            val result = context.capabilities.request(
                CapabilityRequest(Capability.NOTIFICATIONS, "Greet you with a notification."),
            )
            permission = if (result is CapabilityResult.Granted) "granted" else "denied"
            context.log.info("NOTIFICATIONS: $result")
            render(context)
        }
    }

    private fun render(context: ModuleContext) {
        context.ui.show(
            column {
                text(if (name.isBlank()) "Hello from the sandbox" else "Hello, $name", TextStyle.TITLE)
                textField(ID_NAME, name, label = "Your name")
                row {
                    button(ID_COUNT, "Count")
                    text("Clicks: $clicks")
                }
                row {
                    button(ID_PERMISSION, "Ask for notifications")
                    text(permission, TextStyle.CAPTION)
                }
            },
        )
    }

    private companion object {
        const val ID_COUNT = "count"
        const val ID_NAME = "name"
        const val ID_PERMISSION = "permission"
    }
}
