package dev.moduforge.host.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.moduforge.sandbox.DeviceCallException
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Touching the screen and reading it, through [ModuForgeAccessibilityService].
 *
 * Two things are never done, whatever a module asks: nothing is typed into or read from a
 * password field, and nothing is read or operated in the screens where the user decides what an
 * app may do — system settings, the permission and installer dialogs, and ModuForge itself. A
 * module must not be able to approve its own permissions or switch off the protections around it.
 * Going back and home always work, so the user can leave such a screen.
 */
internal class ScreenService(private val context: Context) {

    fun info(): Map<String, Any?> {
        val metrics = context.resources.displayMetrics
        val service = ModuForgeAccessibilityService.instance
        return mapOf(
            "width" to metrics.widthPixels,
            "height" to metrics.heightPixels,
            "package" to (service?.rootInActiveWindow?.packageName?.toString() ?: ""),
            "enabled" to (service != null),
        )
    }

    fun tap(x: Double, y: Double, ms: Long) = stroke(listOf(x to y), ms.coerceIn(1, 500))

    fun press(x: Double, y: Double, ms: Long) = stroke(listOf(x to y), ms.coerceIn(500, 10_000))

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long) = stroke(listOf(x1 to y1, x2 to y2), ms.coerceIn(50, 10_000))

    fun global(action: Int) {
        if (!require().performGlobalAction(action)) throw DeviceCallException("the system refused the action")
    }

    /** Everything readable on the screen: texts, descriptions, positions. */
    fun texts(): List<Map<String, Any?>> = readable { root -> collect(root) { true } }

    fun find(text: String): List<Map<String, Any?>> = readable { root ->
        val hits = collect(root) { matches(it, text) }
        val wanted = text.trim()
        // Elements that say exactly this come first.
        (hits.filter { it["text"].toString().trim().equals(wanted, true) || it["desc"].toString().trim().equals(wanted, true) } +
            hits.filterNot { it["text"].toString().trim().equals(wanted, true) || it["desc"].toString().trim().equals(wanted, true) }).take(MAX_FOUND)
    }

    /** Presses the first element whose text contains [text], or the nearest pressable one around it. */
    fun click(text: String): Map<String, Any?> = readable { root ->
        val target = firstMatch(root, text) ?: throw DeviceCallException("nothing on the screen says \"$text\"")
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && !node.isClickable && depth++ < MAX_CLICK_PARENTS) node = node.parent
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw DeviceCallException("\"$text\" cannot be pressed")
        mapOf("ok" to true, "text" to text)
    }

    /** Types into the field that has the input focus. */
    fun type(text: String): Map<String, Any?> {
        val service = require()
        val root = service.rootInActiveWindow ?: throw DeviceCallException("there is no window to type into")
        refuseForbidden(root)
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw DeviceCallException("no input field has the focus")
        if (field.isPassword) throw DeviceCallException("typing into a password field is not allowed")
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) throw DeviceCallException("the field refused the text")
        return mapOf("ok" to true)
    }

    /** Waits until something on the screen says [text]; the first match, or `found = false`. */
    suspend fun wait(text: String, timeoutSeconds: Double): Map<String, Any?> {
        val deadline = System.nanoTime() + (timeoutSeconds.coerceIn(0.0, MAX_WAIT_SECONDS) * 1e9).toLong()
        var closed: DeviceCallException? = null
        while (true) {
            try {
                val hit = find(text).firstOrNull()
                if (hit != null) return hit + ("found" to true)
                closed = null
            } catch (e: DeviceCallException) {
                // A closed screen (settings, ModuForge itself) or a window that is still opening: keep waiting.
                if (ModuForgeAccessibilityService.instance == null) throw e
                closed = e
            }
            if (System.nanoTime() >= deadline) return closed?.let { throw it } ?: mapOf("found" to false)
            delay(POLL_MILLIS)
        }
    }

    /** The next thing that happened on the screen, or null after [timeoutSeconds]. */
    suspend fun event(timeoutSeconds: Double): Map<String, Any?>? {
        ModuForgeAccessibilityService.capturing = true
        val deadline = System.nanoTime() + (timeoutSeconds.coerceIn(0.0, MAX_WAIT_SECONDS) * 1e9).toLong()
        while (true) {
            ModuForgeAccessibilityService.events.poll()?.let { return it }
            if (System.nanoTime() >= deadline) return null
            delay(POLL_MILLIS)
        }
    }

    // --- internals --------------------------------------------------------------------------

    private fun require(): AccessibilityService = ModuForgeAccessibilityService.instance
        ?: throw DeviceCallException("the accessibility service of ModuForge is off: turn it on in Settings → Accessibility")

    private fun <T> readable(block: (AccessibilityNodeInfo) -> T): T {
        val root = require().rootInActiveWindow ?: throw DeviceCallException("the screen cannot be read right now")
        refuseForbidden(root)
        return block(root)
    }

    private fun refuseForbidden(root: AccessibilityNodeInfo) {
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName in FORBIDDEN_PACKAGES || packageName == context.packageName) {
            throw DeviceCallException("this screen belongs to the system or to ModuForge and is closed to modules")
        }
    }

    private fun stroke(points: List<Pair<Double, Double>>, durationMs: Long) {
        val service = require()
        service.rootInActiveWindow?.let(::refuseForbidden)
        val metrics = context.resources.displayMetrics
        points.forEach { (x, y) ->
            if (x < 0 || y < 0 || x > metrics.widthPixels || y > metrics.heightPixels) {
                throw DeviceCallException("the point ($x, $y) is outside the screen ${metrics.widthPixels}x${metrics.heightPixels}")
            }
        }
        val path = Path().apply {
            moveTo(points.first().first.toFloat(), points.first().second.toFloat())
            // A tap is a stroke that stays; GestureDescription refuses a zero-length path only for swipes.
            if (points.size > 1) lineTo(points.last().first.toFloat(), points.last().second.toFloat()) else lineTo(points.first().first.toFloat() + 0.1f, points.first().second.toFloat())
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val finished = CountDownLatch(1)
        var completed = false
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription) {
                    completed = true
                    finished.countDown()
                }

                override fun onCancelled(description: GestureDescription) = finished.countDown()
            },
            null,
        )
        if (!accepted) throw DeviceCallException("the system refused the gesture")
        finished.await(durationMs + GESTURE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        if (!completed) throw DeviceCallException("the gesture was interrupted")
    }

    private fun matches(node: AccessibilityNodeInfo, text: String): Boolean =
        listOf(node.text, node.contentDescription, node.viewIdResourceName).any { it?.toString()?.contains(text, ignoreCase = true) == true }

    /** The element that says [text] exactly if there is one, otherwise the first that contains it. */
    private fun firstMatch(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        var exact: AccessibilityNodeInfo? = null
        var partial: AccessibilityNodeInfo? = null
        walk(root, 0) { node ->
            if (!node.isVisibleToUser || node.isPassword) return@walk
            if (exact == null && isExactly(node, text)) exact = node
            if (partial == null && matches(node, text)) partial = node
        }
        return exact ?: partial
    }

    private fun isExactly(node: AccessibilityNodeInfo, text: String): Boolean =
        listOf(node.text, node.contentDescription).any { it?.toString()?.trim().equals(text.trim(), ignoreCase = true) }

    private fun collect(root: AccessibilityNodeInfo, accept: (AccessibilityNodeInfo) -> Boolean): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        walk(root, 0) { node ->
            if (out.size >= MAX_NODES || !node.isVisibleToUser) return@walk
            val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if ((text.isEmpty() && description.isEmpty() && !node.isClickable) || !accept(node)) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            out += mapOf(
                "text" to text.take(MAX_TEXT),
                "desc" to description.take(MAX_TEXT),
                "id" to id,
                "x" to bounds.centerX(),
                "y" to bounds.centerY(),
                "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                "clickable" to node.isClickable,
                "password" to node.isPassword,
            )
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > MAX_DEPTH) return
        visit(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    companion object {
        /** Screens where the user decides what apps may do; closed to modules. */
        val FORBIDDEN_PACKAGES = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
            "dev.moduforge.host",
            "dev.moduforge.host.debug",
        )

        private const val MAX_DEPTH = 60
        private const val MAX_NODES = 500
        private const val MAX_FOUND = 50
        private const val MAX_TEXT = 300
        private const val MAX_CLICK_PARENTS = 6
        private const val MAX_WAIT_SECONDS = 25.0
        private const val POLL_MILLIS = 250L
        private const val GESTURE_GRACE_MILLIS = 5_000L
    }
}
