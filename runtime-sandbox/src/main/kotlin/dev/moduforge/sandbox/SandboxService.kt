package dev.moduforge.sandbox

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import dev.moduforge.core.module.LifecyclePhase
import dev.moduforge.core.module.ModuleLogSink
import dev.moduforge.sandbox.ipc.IHostBridge
import dev.moduforge.sandbox.ipc.IResultCallback
import dev.moduforge.sandbox.ipc.ISandbox
import dev.moduforge.sandbox.ipc.IConnectCallback
import dev.moduforge.script.ScriptRuntimes
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityNotGrantedException
import dev.moduforge.sdk.Connection
import dev.moduforge.sdk.NetworkGateway
import dev.moduforge.sdk.DeviceGateway
import dev.moduforge.sdk.DeviceServiceCatalog
import dev.moduforge.sdk.NotificationGateway
import dev.moduforge.sdk.StorageGateway
import dev.moduforge.sdk.UserPrompt
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import dev.moduforge.sdk.CapabilityGateway
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ModuleLogger
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.ui.UiEvent
import dev.moduforge.sdk.ui.UiNode
import dev.moduforge.sdk.ui.UiSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JSON used on both ends of the sandbox IPC. */
internal val IpcJson = Json

/**
 * Module side of the sandbox. Runs in an isolated process, hosts exactly one module
 * and reaches the host only through the [IHostBridge] it was handed.
 */
class SandboxService : Service() {

    private class Loaded(val module: Module, val context: ModuleContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var loaded: Loaded? = null

    /** True between a completed `onStart` and a completed `onStop`. */
    @Volatile
    private var started = false

    private val binder = object : ISandbox.Stub() {
        override fun load(
            modulePackage: ParcelFileDescriptor,
            manifestJson: String,
            bridge: IHostBridge,
            callback: IResultCallback,
        ) = reply(callback) {
            check(loaded == null) { "a module is already loaded" }
            val manifest = (ModuleManifests.parse(manifestJson) as? ManifestResult.Valid)?.manifest
                ?: error("invalid manifest")
            val module = withContext(Dispatchers.IO) { ModuleLoader.load(modulePackage, manifest) }
            loaded = Loaded(module, BridgeModuleContext(manifest, bridge))
        }

        override fun invoke(phase: String, callback: IResultCallback) = reply(callback) {
            val current = loaded ?: error("no module loaded")
            val module = current.module
            val context = current.context
            when (LifecyclePhase.valueOf(phase)) {
                LifecyclePhase.INSTALL -> module.onInstall(context)
                LifecyclePhase.ENABLE -> module.onEnable(context)
                LifecyclePhase.START -> module.onStart(context)
                LifecyclePhase.STOP -> module.onStop(context)
                LifecyclePhase.DISABLE -> module.onDisable(context)
                LifecyclePhase.UNINSTALL -> module.onUninstall(context)
            }
            started = when (LifecyclePhase.valueOf(phase)) {
                LifecyclePhase.START -> true
                LifecyclePhase.STOP -> false
                else -> started
            }
        }

        override fun uiEvent(eventJson: String) {
            val current = loaded?.takeIf { started } ?: return
            scope.launch {
                try {
                    current.module.onUiEvent(current.context, IpcJson.decodeFromString(UiEvent.serializer(), eventJson))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    current.context.log.error("onUiEvent failed", t)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun reply(callback: IResultCallback, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                callback.onSuccess("")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                callback.onFailure(t.toString())
            }
        }
    }
}

/** Turns a module package into a live [Module] without touching the file system. */
internal object ModuleLoader {
    private const val MAX_CODE_BYTES = 64L * 1024 * 1024
    private const val CODE_PREFIX = "code/"
    private val DEX_ENTRY = Regex("""classes\d*\.dex""")

    /** Runtimes this sandbox can execute. */
    val SUPPORTED_RUNTIMES = setOf(ModuleRuntimeKind.DEX) + ScriptRuntimes.SUPPORTED

    fun load(modulePackage: ParcelFileDescriptor, manifest: ModuleManifest): Module {
        val code = ParcelFileDescriptor.AutoCloseInputStream(modulePackage).use(::readCode)
        return when (manifest.runtime) {
            ModuleRuntimeKind.DEX -> loadDex(code, manifest.entry)
            in ScriptRuntimes.SUPPORTED -> ScriptRuntimes.create(manifest.runtime, code, manifest.entry)
            else -> error("runtime ${manifest.runtime.name.lowercase()} is not supported by this host")
        }
    }

    private fun loadDex(code: Map<String, ByteArray>, entry: String): Module {
        val dex = code.filterKeys(DEX_ENTRY::matches)
            .toSortedMap(compareBy<String> { it.length }.thenBy { it })
            .values.map(ByteBuffer::wrap)
        require(dex.isNotEmpty()) { "module package contains no code" }
        val parent = SdkOnlyClassLoader(Module::class.java.classLoader!!)
        val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            InMemoryDexClassLoader(dex.toTypedArray(), parent)
        } else {
            require(dex.size == 1) { "multi-dex module packages need Android 8.1" }
            InMemoryDexClassLoader(dex.single(), parent)
        }
        val instance = loader.loadClass(entry).getDeclaredConstructor().newInstance()
        return instance as? Module ?: error("$entry does not implement Module")
    }

    /**
     * Reads the code of a package into memory, keyed by path relative to `code/`.
     * Host-bundled packages in APK layout keep their dex files at the archive root.
     */
    private fun readCode(input: InputStream): Map<String, ByteArray> {
        val code = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = when {
                    entry.isDirectory -> continue
                    entry.name.startsWith(CODE_PREFIX) -> entry.name.removePrefix(CODE_PREFIX)
                    DEX_ENTRY.matches(entry.name) -> entry.name
                    else -> continue
                }
                val bytes = zip.readBytes()
                total += bytes.size
                require(total <= MAX_CODE_BYTES) { "module code exceeds $MAX_CODE_BYTES bytes" }
                code[path] = bytes
            }
        }
        return code
    }
}

/**
 * Parent class loader of module code: exposes the platform plus the module SDK and its
 * Kotlin runtime, and hides every other class of the host APK. This is API hygiene;
 * the security boundary is the isolated process.
 */
internal class SdkOnlyClassLoader(private val app: ClassLoader) : ClassLoader(Any::class.java.classLoader) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (SHARED_PREFIXES.any(name::startsWith)) app.loadClass(name) else super.loadClass(name, resolve)

    private companion object {
        val SHARED_PREFIXES = listOf("dev.moduforge.sdk.", "kotlin.", "kotlinx.")
    }
}

/** Module's end of a connection relayed by the host. */
internal class StreamConnection(private val descriptor: ParcelFileDescriptor) : Connection {
    override val input: InputStream = FileInputStream(descriptor.fileDescriptor)
    override val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    override fun close() {
        runCatching { descriptor.close() }
    }
}

/** [ModuleContext] whose every member is a call over [IHostBridge]. */
internal class BridgeModuleContext(
    override val manifest: ModuleManifest,
    private val bridge: IHostBridge,
) : ModuleContext {

    override val capabilities = object : CapabilityGateway {
        override suspend fun request(request: CapabilityRequest): CapabilityResult =
            suspendCancellableCoroutine { continuation ->
                val callback = object : IResultCallback.Stub() {
                    override fun onSuccess(payload: String) {
                        continuation.resume(IpcJson.decodeFromString(CapabilityResult.serializer(), payload))
                    }

                    override fun onFailure(message: String) {
                        continuation.resumeWithException(IllegalStateException(message))
                    }
                }
                bridge.requestCapability(IpcJson.encodeToString(CapabilityRequest.serializer(), request), callback)
            }

        override suspend fun isGranted(capability: Capability, target: String?): Boolean =
            withContext(Dispatchers.IO) { bridge.isGranted(capability.name, target) }
    }

    override val log = object : ModuleLogger {
        override fun info(message: String) = send(ModuleLogSink.Level.INFO, message)

        override fun warn(message: String) = send(ModuleLogSink.Level.WARN, message)

        override fun error(message: String, cause: Throwable?) =
            send(ModuleLogSink.Level.ERROR, if (cause == null) message else "$message: $cause")

        private fun send(level: ModuleLogSink.Level, message: String) {
            runCatching { bridge.log(level.ordinal, message) }
        }
    }

    override val network = object : NetworkGateway {
        override suspend fun connect(host: String, port: Int, tls: Boolean): Connection =
            suspendCancellableCoroutine { continuation ->
                val callback = object : IConnectCallback.Stub() {
                    override fun onConnected(stream: ParcelFileDescriptor) {
                        // A cancelled caller will never close the stream; release it here.
                        continuation.resume(StreamConnection(stream)) { _, connection, _ -> connection.close() }
                    }

                    override fun onFailure(notGranted: Boolean, message: String) {
                        continuation.resumeWithException(
                            if (notGranted) CapabilityNotGrantedException(Capability.NETWORK_OUTBOUND) else IOException(message),
                        )
                    }
                }
                bridge.connect(host, port, tls, callback)
            }
    }

    override val storage = object : StorageGateway {
        override suspend fun read(path: String): ByteArray? = stored { bridge.storageRead(path) }

        override suspend fun write(path: String, data: ByteArray) = stored { bridge.storageWrite(path, data) }

        override suspend fun delete(path: String): Boolean = stored { bridge.storageDelete(path) }

        override suspend fun list(): List<String> = stored { bridge.storageList().toList() }

        private suspend fun <T> stored(call: () -> T): T = withContext(Dispatchers.IO) {
            try {
                call()
            } catch (e: SecurityException) {
                throw CapabilityNotGrantedException(Capability.FILE_SANDBOXED)
            } catch (e: IllegalArgumentException) {
                throw IOException(e.message)
            } catch (e: IllegalStateException) {
                throw IOException(e.message)
            }
        }
    }

    override fun stopSelf(reason: String) {
        runCatching { bridge.stopSelf(reason) }
    }

    override val prompt = object : UserPrompt {
        override suspend fun ask(question: String, secret: Boolean): String? =
            suspendCancellableCoroutine { continuation ->
                val callback = object : IResultCallback.Stub() {
                    override fun onSuccess(payload: String) = continuation.resume(payload)

                    override fun onFailure(message: String) = continuation.resume(null)
                }
                bridge.askUser(question, secret, callback)
            }
    }

    override val device = object : DeviceGateway {
        override suspend fun call(service: String, method: String, argsJson: String): String = withContext(Dispatchers.IO) {
            try {
                bridge.deviceCall(service, method, argsJson)
            } catch (e: SecurityException) {
                throw CapabilityNotGrantedException(DeviceServiceCatalog.capabilityOf(service) ?: Capability.DEVICE_INFO)
            } catch (e: IllegalStateException) {
                throw IOException(e.message)
            }
        }
    }

    override val notifications = object : NotificationGateway {
        override suspend fun notify(title: String, text: String) = withContext(Dispatchers.IO) {
            try {
                bridge.notifyUser(title, text)
            } catch (e: SecurityException) {
                throw CapabilityNotGrantedException(Capability.NOTIFICATIONS)
            } catch (e: IllegalStateException) {
                throw IOException(e.message)
            }
        }
    }

    override val ui = object : UiSurface {
        override fun show(root: UiNode) {
            runCatching { bridge.showUi(IpcJson.encodeToString(UiNode.serializer(), root)) }
        }

        override fun clear() {
            runCatching { bridge.showUi(null) }
        }
    }
}
