package dev.moduforge.core.module

import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import kotlinx.coroutines.flow.Flow

/** Lifecycle callbacks of `dev.moduforge.sdk.Module`, in delivery order. */
enum class LifecyclePhase { INSTALL, ENABLE, START, STOP, DISABLE, UNINSTALL }

/** @property reason the module's own explanation; untrusted. */
data class StopRequest(val moduleId: String, val reason: String)

sealed interface RuntimeResult {
    data object Ok : RuntimeResult

    data class Failed(val reason: String) : RuntimeResult
}

/**
 * Executes module code in isolation from the host. One sandbox per module;
 * the host talks to it over IPC only and can destroy it at any moment.
 */
interface ModuleRuntime {
    /** Kinds of module code this runtime can execute. */
    val supportedRuntimes: Set<ModuleRuntimeKind>

    /** Creates a fresh sandbox and loads the module into it. Fails when a sandbox for the module is alive. */
    suspend fun launch(record: ModuleRecord): RuntimeResult

    /** Runs a lifecycle callback inside the live sandbox and waits for it to return. */
    suspend fun invoke(moduleId: String, phase: LifecyclePhase): RuntimeResult

    /** Destroys the sandbox without the module's cooperation. No-op when none is alive. */
    fun kill(moduleId: String)

    fun isAlive(moduleId: String): Boolean

    /** Forwards user interaction with the module's UI. Dropped when no sandbox is alive. */
    fun sendUiEvent(moduleId: String, event: UiEvent)

    /** Ids of modules whose sandbox died for a reason other than [kill]. */
    val unexpectedExits: Flow<String>

    /** Modules that asked to be stopped, e.g. a script that ran to its end. */
    val stopRequests: Flow<StopRequest>
}

/** Receives the UI trees modules ask to display. Content is untrusted. */
fun interface ModuleUiSink {
    /** A null [root] removes the module's UI. */
    fun show(moduleId: String, root: UiNode?)
}

/** Puts a module's question to the user. Content is untrusted. */
fun interface UserInputPrompter {
    /** Suspends until the user answers; null when the question was dismissed. */
    suspend fun ask(moduleId: String, moduleName: String, question: String, secret: Boolean): String?
}

/** Shows notifications on behalf of modules. Content is untrusted. */
fun interface ModuleNotifier {
    /** @return false when the system does not let the host post notifications. */
    fun notify(moduleId: String, moduleName: String, title: String, text: String): Boolean
}

/** Collects diagnostic output of modules. Content is untrusted. */
interface ModuleLogSink {
    fun append(moduleId: String, level: Level, message: String)

    enum class Level { INFO, WARN, ERROR }
}
