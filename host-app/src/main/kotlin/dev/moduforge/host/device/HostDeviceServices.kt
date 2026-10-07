package dev.moduforge.host.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.audit.AuditEvent
import dev.moduforge.core.audit.AuditEventType
import dev.moduforge.core.audit.AuditLog
import dev.moduforge.core.permission.PermissionBroker
import dev.moduforge.host.MainActivity
import dev.moduforge.host.R
import dev.moduforge.sandbox.DeviceCallException
import dev.moduforge.sandbox.DeviceServices
import dev.moduforge.sandbox.ModuleStorage
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.DeviceServiceCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device services of the host: apps, screen, camera. The permission has been checked by the
 * time a call arrives. Here every call is audited and, while a module uses the camera or the
 * screen, a notification says so; the notification disappears a minute after the last call.
 */
@Singleton
class HostDeviceServices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audit: AuditLog,
    private val broker: PermissionBroker,
    storage: ModuleStorage,
) : DeviceServices {

    private val apps = AppsService(context)
    private val screen = ScreenService(context)
    private val camera = CameraService(context, storage)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val quietUntil = ConcurrentHashMap<String, Long>()

    override suspend fun call(moduleId: String, moduleName: String, service: String, method: String, argsJson: String): String {
        val args = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw DeviceCallException("the arguments are not a JSON object")
        val capability = DeviceServiceCatalog.capabilityOf(service) ?: throw DeviceCallException("there is no device service '$service'")

        record(moduleId, capability, service, method, args)
        if (service != DeviceServiceCatalog.APPS) announce(moduleId, moduleName, service)

        val result: Any? = when (service) {
            DeviceServiceCatalog.APPS -> callApps(method, args)
            DeviceServiceCatalog.SCREEN -> callScreen(method, args)
            else -> callCamera(moduleId, method, args)
        }
        return Json.encodeToString(JsonElement.serializer(), toJson(result))
    }

    private fun callApps(method: String, args: JsonObject): Any? = when (method) {
        "list" -> apps.list()
        "launch" -> apps.launch(args.text("app"))
        "open" -> apps.open(args.text("url"))
        "installed" -> apps.installed(args.text("package"))
        else -> unknown(DeviceServiceCatalog.APPS, method)
    }

    private suspend fun callScreen(method: String, args: JsonObject): Any? = when (method) {
        "info" -> screen.info()
        "tap" -> screen.tap(args.number("x"), args.number("y"), args.optNumber("ms", 50.0).toLong()).done()
        "press" -> screen.press(args.number("x"), args.number("y"), args.optNumber("ms", 700.0).toLong()).done()
        "swipe" -> screen.swipe(args.number("x1"), args.number("y1"), args.number("x2"), args.number("y2"), args.optNumber("ms", 300.0).toLong()).done()
        "back" -> screen.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK).done()
        "home" -> screen.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME).done()
        "recents" -> screen.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS).done()
        "notifications" -> screen.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS).done()
        "texts" -> screen.texts()
        "find" -> screen.find(args.text("text"))
        "click" -> screen.click(args.text("text"))
        "type" -> screen.type(args.text("text"))
        "wait" -> screen.wait(args.text("text"), args.optNumber("timeout", 10.0))
        "event" -> screen.event(args.optNumber("timeout", 10.0))
        else -> unknown(DeviceServiceCatalog.SCREEN, method)
    }

    private suspend fun callCamera(moduleId: String, method: String, args: JsonObject): Any? = when (method) {
        "list" -> camera.list()
        "photo" -> {
            // The photo is written into the module's storage, which therefore has to be allowed.
            if (!broker.isGranted(moduleId, Capability.FILE_SANDBOXED)) {
                throw DeviceCallException("camera.photo saves into the module's storage: the module needs ${Capability.FILE_SANDBOXED.name}")
            }
            camera.photo(
                moduleId = moduleId,
                path = args.text("path"),
                lens = args.optText("lens", "back"),
                size = args.optNumber("size", CameraService.DEFAULT_SIZE.toDouble()).toInt(),
                quality = args.optNumber("quality", CameraService.DEFAULT_QUALITY.toDouble()).toInt(),
                flash = args.optText("flash", "off"),
            )
        }
        else -> unknown(DeviceServiceCatalog.CAMERA, method)
    }

    private fun Unit.done(): Map<String, Any?> = mapOf("ok" to true)

    private fun unknown(service: String, method: String): Nothing = throw DeviceCallException("$service has no call named '$method'")

    /** Reading calls are audited once a minute per module; calls that act, and every photo, every time. */
    private suspend fun record(moduleId: String, capability: Capability, service: String, method: String, args: JsonObject) {
        val reading = service == DeviceServiceCatalog.SCREEN && method in READING_CALLS
        if (reading) {
            val now = System.currentTimeMillis()
            if (now < (quietUntil[moduleId + method] ?: 0)) return
            quietUntil[moduleId + method] = now + READ_AUDIT_INTERVAL_MILLIS
        }
        val detail = args.entries.joinToString(", ") { (key, value) ->
            // What a module types is not copied into the log.
            if (key == "text" && method == "type") "text=…" else "$key=${(value as? JsonPrimitive)?.content.orEmpty().take(40)}"
        }
        audit.record(
            AuditEvent(
                timestampMs = System.currentTimeMillis(),
                moduleId = moduleId,
                type = AuditEventType.DEVICE_CALL,
                capability = capability,
                target = "$service.$method",
                detail = detail,
            ),
        )
    }

    /** Tells the user that a module is using the camera or the screen right now. */
    private fun announce(moduleId: String, moduleName: String, service: String) {
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.device_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = context.getString(if (service == DeviceServiceCatalog.CAMERA) R.string.device_using_camera else R.string.device_using_screen, moduleName)
        notifications.notify(
            moduleId + service,
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(text)
                .setContentIntent(open)
                .setTimeoutAfter(NOTICE_MILLIS)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
        is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull ?: throw DeviceCallException("'$name' is missing")

    private fun JsonObject.optText(name: String, default: String): String = (this[name] as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.number(name: String): Double =
        (this[name] as? JsonPrimitive)?.doubleOrNull ?: throw DeviceCallException("'$name' must be a number")

    private fun JsonObject.optNumber(name: String, default: Double): Double = (this[name] as? JsonPrimitive)?.doubleOrNull ?: default

    private companion object {
        const val CHANNEL_ID = "device-activity"
        const val NOTIFICATION_ID = 3
        const val NOTICE_MILLIS = 60_000L
        const val READ_AUDIT_INTERVAL_MILLIS = 60_000L
        val READING_CALLS = setOf("info", "texts", "find", "wait", "event")
    }
}
