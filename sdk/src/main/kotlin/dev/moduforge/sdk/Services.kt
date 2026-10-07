package dev.moduforge.sdk

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A host service was used without holding the capability it needs. Request the capability first. */
public class CapabilityNotGrantedException(public val capability: Capability) :
    Exception("${capability.name} is not granted")

/** Byte stream to a remote host, opened by the host on the module's behalf. */
public interface Connection : Closeable {
    public val input: InputStream

    public val output: OutputStream
}

/** Outbound networking. Requires [Capability.NETWORK_OUTBOUND]; every connection is audited. */
public interface NetworkGateway {
    /**
     * Opens a TCP connection to a public internet address.
     *
     * @param tls when true the host performs the TLS handshake and certificate validation;
     * the returned stream carries the decrypted data.
     * @throws CapabilityNotGrantedException when the capability is not granted.
     * @throws java.io.IOException when the address is not public or the connection fails.
     */
    public suspend fun connect(host: String, port: Int, tls: Boolean = false): Connection
}

/**
 * Services of the device itself, reached by name: `apps`, `screen` and `camera`. Each needs its own
 * capability ([Capability.LAUNCH_APPS], [Capability.SCREEN_CONTROL], [Capability.CAMERA]); every call is
 * audited and, while a module uses the camera or the screen, the host shows that it does.
 *
 * Arguments and results are JSON texts so that the set of calls can grow without changing this interface.
 * The calls themselves are described in the script API reference.
 */
public interface DeviceGateway {
    /**
     * @param service `apps`, `screen` or `camera`.
     * @param method name of the call within the service.
     * @param argsJson JSON object with the arguments.
     * @return JSON text of the result.
     * @throws CapabilityNotGrantedException when the capability of the service is not granted.
     * @throws java.io.IOException when the call fails: no such service, the device cannot do it, the user has not enabled it.
     */
    public suspend fun call(service: String, method: String, argsJson: String = "{}"): String
}

/** The device services by name and the capability each one needs. */
public object DeviceServiceCatalog {
    public const val APPS: String = "apps"
    public const val SCREEN: String = "screen"
    public const val CAMERA: String = "camera"

    /** Capability that opens [service], or null when there is no such service. */
    public fun capabilityOf(service: String): Capability? = when (service) {
        APPS -> Capability.LAUNCH_APPS
        SCREEN -> Capability.SCREEN_CONTROL
        CAMERA -> Capability.CAMERA
        else -> null
    }
}

/** What [ModuleContext.device] is on a host that offers no device services. */
public object NoDeviceServices : DeviceGateway {
    override suspend fun call(service: String, method: String, argsJson: String): String =
        throw java.io.IOException("this host offers no device services")
}

/** Questions the module puts to the user through a dialog drawn by the host. */
public interface UserPrompt {
    /**
     * Asks the user to type an answer. The dialog names the module and warns that the answer goes to it.
     *
     * @param secret hide the typed text, for tokens and codes.
     * @return the answer, or null when the user dismissed the question.
     */
    public suspend fun ask(question: String, secret: Boolean = false): String?
}

/** Notifications shown to the user under the module's name. Requires [Capability.NOTIFICATIONS]. */
public interface NotificationGateway {
    /**
     * Shows a notification, replacing the module's previous one.
     *
     * @throws CapabilityNotGrantedException when the capability is not granted.
     * @throws java.io.IOException when notifications are turned off for the host.
     */
    public suspend fun notify(title: String, text: String)
}

/**
 * Private storage of the module, kept encrypted by the host and removed on uninstall.
 * Requires [Capability.FILE_SANDBOXED]. Paths are relative, `/`-separated.
 */
public interface StorageGateway {
    /** @return the file content, or null when the file does not exist. */
    public suspend fun read(path: String): ByteArray?

    /** Creates or replaces a file. @throws java.io.IOException when a size limit is exceeded. */
    public suspend fun write(path: String, data: ByteArray)

    /** @return true when a file was removed. */
    public suspend fun delete(path: String): Boolean

    /** Paths of all files of the module. */
    public suspend fun list(): List<String>
}
