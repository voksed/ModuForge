package dev.moduforge.sandbox

/** A device call that cannot be done; the message is shown to the module as the reason. */
class DeviceCallException(message: String) : Exception(message)

/**
 * The device services the host offers to modules: apps, screen, camera. The host app implements
 * it with Android APIs; the permission check has already happened when [call] is reached.
 */
interface DeviceServices {
    /**
     * @param argsJson JSON object with the arguments.
     * @return JSON text of the result.
     * @throws DeviceCallException when the call is unknown or the device cannot do it.
     */
    suspend fun call(moduleId: String, moduleName: String, service: String, method: String, argsJson: String): String
}

/** Longest a module waits for one device call, e.g. for a photo. */
internal const val DEVICE_CALL_TIMEOUT_MILLIS = 30_000L
