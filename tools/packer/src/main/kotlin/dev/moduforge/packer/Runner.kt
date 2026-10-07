package dev.moduforge.packer

import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.core.net.NetworkPolicy
import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.script.ScriptRuntimes
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityGateway
import dev.moduforge.sdk.CapabilityNotGrantedException
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.Connection
import dev.moduforge.sdk.DenialReason
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ModuleLogger
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.NetworkGateway
import dev.moduforge.sdk.NotificationGateway
import dev.moduforge.sdk.StorageGateway
import dev.moduforge.sdk.UserPrompt
import dev.moduforge.sdk.ui.UiNode
import dev.moduforge.sdk.ui.UiSurface
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * `mfrg run`: executes a script module on this computer with the runtime the app uses.
 * The host services are desktop stand-ins with the same rules as on a device: a service
 * works only when its permission is declared, and the network reaches only public addresses.
 *
 * @return an empty report: everything worth saying was printed while the module ran.
 * @throws UsageError when the module cannot be run or its script failed.
 */
internal fun runModule(target: File, options: Options, readLine: () -> String?, print: (String) -> Unit): String {
    val project = loadProject(target)
    if (project.manifest.runtime !in ScriptRuntimes.SUPPORTED) {
        throw UsageError("only script modules run on a desktop; this one is '${project.manifest.runtime.name.lowercase()}'")
    }
    val denied = options.optional("deny").orEmpty().split(',').filter { it.isNotBlank() }.map { name ->
        Capability.entries.firstOrNull { it.name == name.trim() } ?: throw UsageError("unknown permission '$name'")
    }.toSet()

    val host = DesktopHost(project, denied, options.switch("allow-local"), readLine, print)
    print("running ${project.manifest.name} ${project.manifest.version} (${languageName(project.manifest.runtime)}), storage in ${host.storageDir}")
    val granted = project.manifest.permissions - denied
    print("permissions: ${granted.joinToString().ifEmpty { "none" }}" + if (denied.isEmpty()) "" else "; denied: ${denied.joinToString()}")

    val module = ScriptRuntimes.create(project.manifest.runtime, project.files, project.manifest.entry)
    runBlocking { module.onStart(host) }
    host.finished.await()
    val reason = host.stopReason
    if (reason.startsWith("script failed")) throw UsageError(reason)
    return ""
}

private const val NEIGHBOUR_DEPTH = 4
private const val MAX_NEIGHBOUR_BYTES = 1024 * 1024L
private const val MAX_NEIGHBOURS = 500

private class Project(val manifest: ModuleManifest, val files: Map<String, ByteArray>, val root: File)

/** A project directory with `moduforge.json`, or a single script with a manifest worked out from its code. */
private fun loadProject(target: File): Project {
    if (target.isFile) {
        val runtime = LocalModules.runtimeForFile(target.name) ?: throw UsageError("${target.name} is not a .lua or .js script")
        val source = target.readText()
        val name = target.nameWithoutExtension
        val manifest = LocalModules.manifest(LocalModules.idFor(name) { false }, name, source, LocalModules.AVAILABLE.toSet(), null, runtime)
        val folder = target.absoluteFile.parentFile
        // Other scripts of the same language next to it, so that require() finds them.
        val neighbours = folder.walkTopDown().maxDepth(NEIGHBOUR_DEPTH)
            .onEnter { it == folder || !it.name.startsWith(".") && it.name != "node_modules" }
            .filter { it.isFile && it != target.absoluteFile && it.extension == target.extension && it.length() <= MAX_NEIGHBOUR_BYTES }
            .map { it.relativeTo(folder).invariantSeparatorsPath to it }
            .filter { (path, _) -> path != manifest.entry && ModuleManifests.isRelativePath(path) }
            .take(MAX_NEIGHBOURS)
            .associate { (path, file) -> path to file.readBytes() }
        return Project(manifest, neighbours + (manifest.entry to source.toByteArray()), folder)
    }
    val manifestFile = File(target, ModulePackageFormat.MANIFEST)
    if (!manifestFile.isFile) throw UsageError("$manifestFile not found; pass a project directory or a .lua/.js file")
    val manifest = when (val parsed = ModuleManifests.parse(manifestFile.readText())) {
        is ManifestResult.Valid -> parsed.manifest
        is ManifestResult.Invalid -> throw UsageError("invalid manifest: ${parsed.problems.joinToString("; ")}")
    }
    val files = projectFiles(target)
    if (manifest.entry !in files) throw UsageError("entry script ${manifest.entry} is missing")
    return Project(manifest, files, target.absoluteFile)
}

/** Desktop implementation of everything a module can reach. */
private class DesktopHost(
    project: Project,
    private val denied: Set<Capability>,
    private val allowLocal: Boolean,
    private val readLine: () -> String?,
    private val print: (String) -> Unit,
) : ModuleContext {

    val storageDir = File(project.root, "$RUN_DIRECTORY/storage/${project.manifest.id}")
    val finished = CountDownLatch(1)

    @Volatile
    var stopReason = "stopped"
        private set

    override val manifest = project.manifest

    private fun holds(capability: Capability) = capability in manifest.permissions && capability !in denied

    private fun require(capability: Capability) {
        if (!holds(capability)) throw CapabilityNotGrantedException(capability)
    }

    override val capabilities = object : CapabilityGateway {
        override suspend fun request(request: CapabilityRequest): CapabilityResult = when {
            request.capability !in manifest.permissions -> CapabilityResult.Denied(DenialReason.NOT_DECLARED)
            request.capability in denied -> CapabilityResult.Denied(DenialReason.USER_DENIED)
            else -> CapabilityResult.Granted
        }

        override suspend fun isGranted(capability: Capability, target: String?) = holds(capability)
    }

    override val log = object : ModuleLogger {
        override fun info(message: String) = print(message)
        override fun warn(message: String) = print("warning: $message")
        override fun error(message: String, cause: Throwable?) = print("error: $message")
    }

    override val network = object : NetworkGateway {
        override suspend fun connect(host: String, port: Int, tls: Boolean): Connection {
            require(Capability.NETWORK_OUTBOUND)
            val address = InetAddress.getAllByName(host).firstOrNull { allowLocal || NetworkPolicy.isPublic(it) }
                ?: throw IOException("destination is not a public internet address")
            var socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                if (tls) {
                    val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
                    secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    secure.startHandshake()
                    socket = secure
                }
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            val connected = socket
            return object : Connection {
                override val input: InputStream = connected.getInputStream()
                override val output: OutputStream = connected.getOutputStream()
                override fun close() {
                    runCatching { connected.close() }
                }
            }
        }
    }

    override val storage = object : StorageGateway {
        private fun file(path: String): File {
            require(Capability.FILE_SANDBOXED)
            if (!ModuleManifests.isRelativePath(path)) throw IOException("invalid storage path")
            return File(storageDir, path)
        }

        override suspend fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

        override suspend fun write(path: String, data: ByteArray) {
            if (data.size > MAX_FILE_BYTES) throw IOException("file exceeds $MAX_FILE_BYTES bytes")
            file(path).apply { parentFile?.mkdirs() }.writeBytes(data)
        }

        override suspend fun delete(path: String): Boolean = file(path).let { it.isFile && it.delete() }

        override suspend fun list(): List<String> {
            require(Capability.FILE_SANDBOXED)
            return storageDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(storageDir).invariantSeparatorsPath }.sorted().toList()
        }
    }

    override val device = SimulatedDevice(
        holds = ::holds,
        store = { path, data ->
            if (!holds(Capability.FILE_SANDBOXED)) throw IOException("camera.photo saves into the module's storage: the module needs FILE_SANDBOXED")
            if (!ModuleManifests.isRelativePath(path)) throw IOException("invalid storage path")
            File(storageDir, path).apply { parentFile?.mkdirs() }.writeBytes(data)
        },
        print = print,
    )

    override val notifications = object : NotificationGateway {
        override suspend fun notify(title: String, text: String) {
            require(Capability.NOTIFICATIONS)
            print(if (text.isEmpty()) "[notification] $title" else "[notification] $title: $text")
        }
    }

    override val prompt = object : UserPrompt {
        override suspend fun ask(question: String, secret: Boolean): String? {
            print("[question] $question")
            val console = System.console()
            return if (secret && console != null) console.readPassword()?.concatToString() else readLine()
        }
    }

    override val ui = object : UiSurface {
        override fun show(root: UiNode) {
            print("[interface]")
            render(root, 1)
        }

        override fun clear() = print("[interface cleared]")

        private fun render(node: UiNode, depth: Int) {
            val indent = "  ".repeat(depth)
            when (node) {
                is UiNode.Column -> node.children.forEach { render(it, depth) }
                is UiNode.Row -> {
                    print("$indent(row)")
                    node.children.forEach { render(it, depth + 1) }
                }
                is UiNode.Text -> print(indent + node.text)
                is UiNode.Button -> print("$indent[ ${node.label} ] id=${node.id}" + if (node.enabled) "" else " (disabled)")
                is UiNode.TextField -> print("$indent${node.label.ifEmpty { node.id }}: [${node.value}] id=${node.id}")
            }
        }
    }

    override fun stopSelf(reason: String) {
        stopReason = reason
        finished.countDown()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        /** The limit of module storage on a device. */
        const val MAX_FILE_BYTES = 512 * 1024
    }
}
