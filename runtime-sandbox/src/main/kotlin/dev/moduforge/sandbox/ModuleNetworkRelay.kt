package dev.moduforge.sandbox

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.net.NetworkPolicy
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityNotGrantedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Network access for modules. A sandbox cannot open sockets; the host opens each connection
 * after checking the grant and relays bytes between the remote peer and a local socket whose
 * other end is handed to the module. The host therefore sees, audits and can cut every
 * connection of a module.
 */
class ModuleNetworkRelay(
    private val broker: PermissionBroker,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Link(val remote: Socket, val local: ParcelFileDescriptor) : Closeable {
        override fun close() {
            runCatching { remote.close() }
            // Shutting down first wakes the relay thread blocked on the local socket and lets the
            // module read what was already delivered before it sees end of stream.
            runCatching { Os.shutdown(local.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { local.close() }
        }
    }

    private val links = ConcurrentHashMap<String, MutableSet<Link>>()

    /**
     * Connects to a public address and returns the module's end of the relay.
     *
     * @throws CapabilityNotGrantedException when `NETWORK_OUTBOUND` is not granted.
     * @throws IOException when the destination is refused or unreachable.
     */
    suspend fun connect(moduleId: String, host: String, port: Int, tls: Boolean): ParcelFileDescriptor {
        val target = "$host:$port"
        if (!broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND)) {
            log(moduleId, AuditEventType.NETWORK_REFUSED, target, "permission not granted")
            throw CapabilityNotGrantedException(Capability.NETWORK_OUTBOUND)
        }
        val open = links.getOrPut(moduleId) { ConcurrentHashMap.newKeySet() }
        return try {
            if (port !in 1..65535) throw IOException("invalid port")
            if (open.size >= MAX_CONNECTIONS) throw IOException("too many open connections")
            val remote = withContext(Dispatchers.IO) { open(host, port, tls) }
            val (hostEnd, moduleEnd) = ParcelFileDescriptor.createSocketPair()
            val link = Link(remote, hostEnd)
            open += link
            pump(link, open, remote.getInputStream(), FileOutputStream(hostEnd.fileDescriptor))
            pump(link, open, FileInputStream(hostEnd.fileDescriptor), remote.getOutputStream())
            log(moduleId, AuditEventType.NETWORK_CONNECTED, target, if (tls) "TLS" else "plain TCP")
            moduleEnd
        } catch (e: IOException) {
            log(moduleId, AuditEventType.NETWORK_REFUSED, target, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Cuts every connection of a module. */
    fun closeAll(moduleId: String) {
        links.remove(moduleId)?.forEach(Link::close)
    }

    /** Cuts the connections of modules whose grant was revoked. Runs until cancelled. */
    suspend fun enforceRevocations() {
        while (true) {
            delay(REVOCATION_CHECK_MS)
            links.forEach { (moduleId, open) ->
                if (open.isNotEmpty() && !broker.isGranted(moduleId, Capability.NETWORK_OUTBOUND)) {
                    closeAll(moduleId)
                    log(moduleId, AuditEventType.NETWORK_REFUSED, null, "connections closed: permission revoked")
                }
            }
        }
    }

    /** Resolves [host] once and connects to the resolved address, so the check and the connection agree. */
    private fun open(host: String, port: Int, tls: Boolean): Socket {
        val address = InetAddress.getAllByName(host).firstOrNull(NetworkPolicy::isPublic)
            ?: throw IOException("destination is not a public internet address")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            if (!tls) return socket
            val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
            secure.soTimeout = CONNECT_TIMEOUT_MS
            secure.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, secure.session)) {
                throw SSLPeerUnverifiedException("certificate does not match $host")
            }
            secure.soTimeout = 0
            return secure
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun pump(link: Link, open: MutableSet<Link>, from: InputStream, to: OutputStream) {
        thread(name = "module-net", isDaemon = true) {
            try {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    to.write(buffer, 0, read)
                    to.flush()
                }
            } catch (e: IOException) {
                // Either side went away; the link is closed below.
            } finally {
                open -= link
                link.close()
            }
        }
    }

    private suspend fun log(moduleId: String, type: AuditEventType, target: String?, detail: String) {
        audit.record(
            AuditEvent(
                timestampMs = clock(),
                moduleId = moduleId,
                type = type,
                capability = Capability.NETWORK_OUTBOUND,
                target = target,
                detail = detail,
            ),
        )
    }

    private companion object {
        const val MAX_CONNECTIONS = 16
        const val CONNECT_TIMEOUT_MS = 15_000
        const val REVOCATION_CHECK_MS = 2_000L
    }
}
