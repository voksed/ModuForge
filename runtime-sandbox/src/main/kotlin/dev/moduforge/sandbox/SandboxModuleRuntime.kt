package dev.moduforge.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.moduforge.core.module.LifecyclePhase
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.core.module.ModuleNotifier
import dev.moduforge.core.module.StopRequest
import dev.moduforge.core.module.UserInputPrompter
import dev.moduforge.core.module.ModuleRecord
import dev.moduforge.core.module.ModuleRuntime
import dev.moduforge.core.module.RuntimeResult
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.sandbox.ipc.IConnectCallback
import dev.moduforge.sandbox.ipc.IHostBridge
import dev.moduforge.sandbox.ipc.IResultCallback
import dev.moduforge.sandbox.ipc.ISandbox
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.DeviceServiceCatalog
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.core.module.ModuleUiSink
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.UiKind
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import dev.moduforge.sdk.CapabilityNotGrantedException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Host side of the sandbox: one isolated process per module, created by binding
 * [SandboxService] and destroyed by releasing the binding. On Android 10+ every module
 * gets its own process; older versions provide a single sandbox process, so only one
 * module can be alive at a time.
 */
class SandboxModuleRuntime(
    context: Context,
    private val packages: ModulePackageStore,
    private val broker: PermissionBroker,
    private val logs: ModuleLogSink,
    private val ui: ModuleUiSink,
    private val network: ModuleNetworkRelay,
    private val storage: ModuleStorage,
    private val notifier: ModuleNotifier,
    private val input: UserInputPrompter,
    private val scope: CoroutineScope,
    private val devices: DeviceServices? = null,
) : ModuleRuntime {

    private val context = context.applicationContext

    init {
        scope.launch { network.enforceRevocations() }
    }

    private class Session(val connection: ServiceConnection) {
        @Volatile
        var sandbox: ISandbox? = null
        val pending: MutableSet<CancellableContinuation<RuntimeResult>> = ConcurrentHashMap.newKeySet()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val launches = AtomicInteger()
    private val exits = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val unexpectedExits: Flow<String> = exits

    private val stops = MutableSharedFlow<StopRequest>(extraBufferCapacity = 16)

    override val stopRequests: Flow<StopRequest> = stops

    override val supportedRuntimes: Set<ModuleRuntimeKind> = ModuleLoader.SUPPORTED_RUNTIMES

    override fun isAlive(moduleId: String): Boolean = sessions.containsKey(moduleId)

    override suspend fun launch(record: ModuleRecord): RuntimeResult {
        val moduleId = record.id
        val file = packages.packageFile(moduleId)
        if (!file.isFile) return RuntimeResult.Failed("module package is missing")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && sessions.isNotEmpty()) {
            return RuntimeResult.Failed("this Android version runs one module at a time")
        }

        if (sessions.containsKey(moduleId)) return RuntimeResult.Failed("sandbox is already alive")

        // A sandbox requested right after the previous one of the same module was released can be
        // handed the process that is still being torn down; it then dies during launch.
        var result = launchOnce(record, file)
        var attempt = 1
        while (result is RuntimeResult.Failed && !sessions.containsKey(moduleId) && attempt < LAUNCH_ATTEMPTS) {
            delay(RELAUNCH_DELAY_MS)
            result = launchOnce(record, file)
            attempt++
        }
        return result
    }

    private suspend fun launchOnce(record: ModuleRecord, file: File): RuntimeResult {
        val moduleId = record.id
        val session = connect(moduleId) ?: return RuntimeResult.Failed("sandbox could not be created")
        val sandbox = session.sandbox ?: return RuntimeResult.Failed("sandbox died while starting")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            call(session) { callback ->
                val bridge = HostBridge(moduleId, record.manifest.name, uiAllowed = record.manifest.ui == UiKind.COMPOSE)
                sandbox.load(descriptor, ModuleManifests.encode(record.manifest), bridge, callback)
            }
        }
    }

    override suspend fun invoke(moduleId: String, phase: LifecyclePhase): RuntimeResult {
        val session = sessions[moduleId] ?: return RuntimeResult.Failed("sandbox is not alive")
        val sandbox = session.sandbox ?: return RuntimeResult.Failed("sandbox is not alive")
        return call(session) { callback -> sandbox.invoke(phase.name, callback) }
    }

    override fun kill(moduleId: String) {
        val session = sessions.remove(moduleId) ?: return
        release(moduleId, session, "sandbox was killed")
        ui.show(moduleId, null)
    }

    override fun sendUiEvent(moduleId: String, event: UiEvent) {
        val sandbox = sessions[moduleId]?.sandbox ?: return
        try {
            sandbox.uiEvent(IpcJson.encodeToString(UiEvent.serializer(), event))
        } catch (e: RemoteException) {
            // The sandbox is gone; its death is reported through the service connection.
        }
    }

    /** Binds a fresh sandbox process. Returns null when the module already has one or binding fails. */
    private suspend fun connect(moduleId: String): Session? = suspendCancellableCoroutine { continuation ->
        lateinit var session: Session
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                session.sandbox = ISandbox.Stub.asInterface(service)
                if (continuation.isActive) continuation.resume(session)
            }

            override fun onServiceDisconnected(name: ComponentName) = died()

            override fun onBindingDied(name: ComponentName) = died()

            override fun onNullBinding(name: ComponentName) = died()

            private fun died() {
                // Releasing the binding also stops the system from restarting the service.
                if (sessions.remove(moduleId, session)) {
                    release(moduleId, session, "sandbox process died")
                    ui.show(moduleId, null)
                    exits.tryEmit(moduleId)
                }
                if (continuation.isActive) continuation.resume(null)
            }
        }
        session = Session(connection)
        if (sessions.putIfAbsent(moduleId, session) != null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val intent = Intent(context, SandboxService::class.java)
        val bound = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A fresh instance name per launch: binding a name whose previous process is still being
                // torn down would attach the module to a process that is about to die.
                val instance = "$moduleId.s${launches.incrementAndGet()}"
                context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, context.mainExecutor, connection)
            } else {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: RuntimeException) {
            false
        }
        if (!bound) {
            sessions.remove(moduleId, session)
            release(moduleId, session, "sandbox could not be created")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation {
            if (sessions.remove(moduleId, session)) release(moduleId, session, "launch was cancelled")
        }
    }

    private fun release(moduleId: String, session: Session, reason: String) {
        network.closeAll(moduleId)
        session.sandbox = null
        runCatching { context.unbindService(session.connection) }
        session.pending.toList().forEach { waiting ->
            if (session.pending.remove(waiting) && waiting.isActive) waiting.resume(RuntimeResult.Failed(reason))
        }
    }

    /** Performs an asynchronous sandbox call; completes early when the sandbox goes away. */
    private suspend fun call(session: Session, send: (IResultCallback) -> Unit): RuntimeResult =
        suspendCancellableCoroutine { continuation ->
            fun finish(result: RuntimeResult) {
                if (session.pending.remove(continuation) && continuation.isActive) continuation.resume(result)
            }
            session.pending += continuation
            continuation.invokeOnCancellation { session.pending.remove(continuation) }
            val callback = object : IResultCallback.Stub() {
                override fun onSuccess(payload: String) = finish(RuntimeResult.Ok)

                override fun onFailure(message: String) = finish(RuntimeResult.Failed(message))
            }
            try {
                send(callback)
            } catch (e: RemoteException) {
                finish(RuntimeResult.Failed("sandbox is unreachable"))
            }
        }

    /** Bridge handed to one sandbox. [moduleId] is fixed by the host and never read from the caller. */
    private inner class HostBridge(
        private val moduleId: String,
        private val moduleName: String,
        private val uiAllowed: Boolean,
    ) : IHostBridge.Stub() {

        override fun requestCapability(requestJson: String, callback: IResultCallback) {
            scope.launch {
                try {
                    val request = IpcJson.decodeFromString(CapabilityRequest.serializer(), requestJson)
                    val bounded = request.copy(rationale = request.rationale.take(MAX_RATIONALE_LENGTH))
                    val result = broker.request(moduleId, bounded)
                    callback.onSuccess(IpcJson.encodeToString(CapabilityResult.serializer(), result))
                } catch (e: RemoteException) {
                    // The sandbox is gone; nobody is waiting for the answer.
                } catch (e: IllegalArgumentException) {
                    runCatching { callback.onFailure("malformed capability request") }
                }
            }
        }

        override fun isGranted(capability: String, target: String?): Boolean {
            val parsed = Capability.entries.firstOrNull { it.name == capability } ?: return false
            return runBlocking { broker.isGranted(moduleId, parsed, target) }
        }

        override fun log(level: Int, message: String) {
            val parsed = ModuleLogSink.Level.entries.getOrElse(level) { ModuleLogSink.Level.INFO }
            logs.append(moduleId, parsed, message.take(MAX_LOG_LENGTH))
        }

        override fun showUi(treeJson: String?) {
            if (!uiAllowed || !sessions.containsKey(moduleId)) return
            if (treeJson == null) return ui.show(moduleId, null)
            val root = treeJson.takeIf { it.length <= MAX_UI_JSON_LENGTH }?.let {
                try {
                    IpcJson.decodeFromString(UiNode.serializer(), it)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            if (root == null || root.size() > MAX_UI_NODES) {
                logs.append(moduleId, ModuleLogSink.Level.WARN, "host: UI tree rejected (malformed or too large)")
                return
            }
            ui.show(moduleId, root)
        }

        override fun connect(host: String, port: Int, tls: Boolean, callback: IConnectCallback) {
            scope.launch {
                try {
                    if (!sessions.containsKey(moduleId)) throw IOException("module is not running")
                    network.connect(moduleId, host, port, tls).use { callback.onConnected(it) }
                } catch (e: CapabilityNotGrantedException) {
                    runCatching { callback.onFailure(true, e.message.orEmpty()) }
                } catch (e: IOException) {
                    runCatching { callback.onFailure(false, e.message ?: e.javaClass.simpleName) }
                } catch (e: RemoteException) {
                    // The sandbox is gone; its connections are closed with the session.
                }
            }
        }

        override fun storageRead(path: String): ByteArray? = stored { storage.read(moduleId, path) }

        override fun storageWrite(path: String, data: ByteArray) = stored { storage.write(moduleId, path, data) }

        override fun storageDelete(path: String): Boolean = stored { storage.delete(moduleId, path) }

        override fun storageList(): Array<String> = stored { storage.list(moduleId).toTypedArray() }

        override fun stopSelf(reason: String) {
            if (sessions.containsKey(moduleId)) stops.tryEmit(StopRequest(moduleId, reason.take(MAX_QUESTION_LENGTH)))
        }

        override fun askUser(question: String, secret: Boolean, callback: IResultCallback) {
            scope.launch {
                try {
                    val answer = if (sessions.containsKey(moduleId)) {
                        input.ask(moduleId, moduleName, question.take(MAX_QUESTION_LENGTH), secret)
                    } else {
                        null
                    }
                    if (answer == null) callback.onFailure("dismissed") else callback.onSuccess(answer)
                } catch (e: RemoteException) {
                    // The sandbox is gone; nobody is waiting for the answer.
                }
            }
        }

        override fun deviceCall(service: String, method: String, argsJson: String): String {
            val offered = devices ?: throw IllegalStateException("this host offers no device services")
            val capability = DeviceServiceCatalog.capabilityOf(service)
                ?: throw IllegalStateException("there is no device service '$service'")
            if (!sessions.containsKey(moduleId)) throw IllegalStateException("the module is not running")
            if (!runBlocking { broker.isGranted(moduleId, capability) }) {
                throw SecurityException("${capability.name} is not granted")
            }
            return try {
                runBlocking { withTimeout(DEVICE_CALL_TIMEOUT_MILLIS) { offered.call(moduleId, moduleName, service, method, argsJson) } }
            } catch (e: DeviceCallException) {
                throw IllegalStateException(e.message)
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("the device did not answer in time")
            }
        }

        override fun notifyUser(title: String, text: String) {
            if (!runBlocking { broker.isGranted(moduleId, Capability.NOTIFICATIONS) }) {
                throw SecurityException("${Capability.NOTIFICATIONS.name} is not granted")
            }
            val shown = notifier.notify(moduleId, moduleName, title.take(MAX_NOTIFICATION_TITLE), text.take(MAX_NOTIFICATION_TEXT))
            if (!shown) throw IllegalStateException("notifications are turned off for ModuForge")
        }

        /** Runs a storage operation for this module; failures cross the binder as the exceptions AIDL can carry. */
        private fun <T> stored(operation: () -> T): T {
            if (!runBlocking { broker.isGranted(moduleId, Capability.FILE_SANDBOXED) }) {
                throw SecurityException("${Capability.FILE_SANDBOXED.name} is not granted")
            }
            return try {
                operation()
            } catch (e: IOException) {
                throw IllegalStateException("storage failure: ${e.message}")
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("stored data is damaged")
            }
        }
    }

    private fun UiNode.size(): Int = when (this) {
        is UiNode.Column -> 1 + children.sumOf { it.size() }
        is UiNode.Row -> 1 + children.sumOf { it.size() }
        else -> 1
    }

    private companion object {
        const val LAUNCH_ATTEMPTS = 3
        const val RELAUNCH_DELAY_MS = 300L
        const val MAX_RATIONALE_LENGTH = 500
        const val MAX_LOG_LENGTH = 4_000
        const val MAX_QUESTION_LENGTH = 500
        const val MAX_NOTIFICATION_TITLE = 80
        const val MAX_NOTIFICATION_TEXT = 1_000
        const val MAX_UI_JSON_LENGTH = 256 * 1024
        const val MAX_UI_NODES = 500
    }
}
