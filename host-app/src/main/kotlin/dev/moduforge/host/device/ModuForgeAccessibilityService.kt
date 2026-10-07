package dev.moduforge.host.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.LinkedBlockingQueue

/**
 * The system accessibility service through which modules with the screen capability touch the
 * screen and read it. The user turns it on in the system settings, and it works only while it is
 * on. It does nothing by itself: [ScreenService] drives it on behalf of a module that holds the grant.
 */
class ModuForgeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!capturing) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName in ScreenService.FORBIDDEN_PACKAGES) return
        val summary: Map<String, Any?> = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> mapOf(
                "type" to "click",
                "package" to packageName,
                // What the user pressed, never what is typed into a field.
                "text" to (event.source?.takeUnless { it.isPassword }?.let { label(it) } ?: ""),
            )
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> mapOf(
                "type" to "window",
                "package" to packageName,
                "title" to event.text.joinToString(" ").take(MAX_EVENT_TEXT),
            )
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> mapOf(
                "type" to "notification",
                "package" to packageName,
                "text" to event.text.joinToString(" ").take(MAX_EVENT_TEXT),
            )
            else -> return
        }
        // A reader that falls behind loses the oldest events, not the newest.
        while (!events.offer(summary)) events.poll()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        events.clear()
        return super.onUnbind(intent)
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription)?.toString()?.take(MAX_EVENT_TEXT).orEmpty()

    companion object {
        private const val MAX_EVENT_TEXT = 200

        /** The connected service, or null while the user has it turned off. */
        @Volatile
        var instance: ModuForgeAccessibilityService? = null
            private set

        /** Events are collected only while a module asks for them. */
        @Volatile
        var capturing: Boolean = false

        val events = LinkedBlockingQueue<Map<String, Any?>>(100)
    }
}
