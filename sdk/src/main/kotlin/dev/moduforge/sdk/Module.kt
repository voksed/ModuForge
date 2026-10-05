package dev.moduforge.sdk

import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiSurface

/**
 * Entry point of a module, named by [ModuleManifest.entry].
 *
 * Callbacks are invoked by the host in order:
 * `onInstall → onEnable → onStart → onStop → onDisable → onUninstall`.
 * Work started in [onStart] must end in [onStop]; the host kills a module that outlives it.
 * Implementations need a public no-argument constructor.
 */
public interface Module {
    public suspend fun onInstall(context: ModuleContext) {}

    public suspend fun onEnable(context: ModuleContext) {}

    public suspend fun onStart(context: ModuleContext) {}

    public suspend fun onStop(context: ModuleContext) {}

    public suspend fun onDisable(context: ModuleContext) {}

    public suspend fun onUninstall(context: ModuleContext) {}

    /** Interaction with the tree last passed to [UiSurface.show]. Delivered only between [onStart] and [onStop]. */
    public suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {}
}

/**
 * The only surface a module has to the host. Every member is backed by IPC;
 * no host object, Android context or shared memory is reachable through it.
 */
public interface ModuleContext {
    public val manifest: ModuleManifest

    public val capabilities: CapabilityGateway

    public val log: ModuleLogger

    public val ui: UiSurface

    public val network: NetworkGateway

    public val storage: StorageGateway

    public val notifications: NotificationGateway

    public val prompt: UserPrompt

    /**
     * Asks the host to stop this module, as if the user had pressed stop. Use it when the
     * module has nothing left to do.
     *
     * @param reason shown to the user in the audit log.
     */
    public fun stopSelf(reason: String = "")
}

/** Diagnostic output of a module, collected by the host. */
public interface ModuleLogger {
    public fun info(message: String)

    public fun warn(message: String)

    public fun error(message: String, cause: Throwable? = null)
}
