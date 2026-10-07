package dev.moduforge.host.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.moduforge.sandbox.DeviceCallException

/** Listing and launching the apps installed on the device, and opening links in them. */
internal class AppsService(private val context: Context) {

    private val packages: PackageManager = context.packageManager

    /** Apps that have an icon in the launcher, sorted by name. */
    fun list(): List<Map<String, Any?>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(query, 0)
            .map { mapOf("package" to it.activityInfo.packageName, "name" to it.loadLabel(packages).toString()) }
            .distinctBy { it["package"] }
            .sortedBy { (it["name"] as String).lowercase() }
    }

    /** Starts an app by its package name or by its name as the launcher shows it. */
    fun launch(app: String): Map<String, Any?> {
        val target = resolve(app) ?: throw DeviceCallException("no app is called \"$app\"")
        if (target == context.packageName) throw DeviceCallException("a module cannot launch ModuForge itself")
        val intent = packages.getLaunchIntentForPackage(target) ?: throw DeviceCallException("$target has no screen to open")
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return mapOf("ok" to true, "package" to target)
    }

    /** Opens a web, mail, map or dial link in whatever app handles it. */
    fun open(url: String): Map<String, Any?> {
        val uri = Uri.parse(url)
        val intent = when (uri.scheme?.lowercase()) {
            "http", "https", "mailto", "geo" -> Intent(Intent.ACTION_VIEW, uri)
            // Dialling only prepares the call; the user still has to press the call button.
            "tel" -> Intent(Intent.ACTION_DIAL, uri)
            else -> throw DeviceCallException("only http, https, mailto, geo and tel links can be opened")
        }
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return mapOf("ok" to true)
    }

    fun installed(packageName: String): Boolean = try {
        packages.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun resolve(app: String): String? {
        val apps = list()
        val wanted = app.trim()
        return apps.firstOrNull { it["package"] == wanted }?.get("package") as String?
            ?: apps.firstOrNull { (it["name"] as String).equals(wanted, ignoreCase = true) }?.get("package") as String?
            ?: apps.firstOrNull { (it["name"] as String).contains(wanted, ignoreCase = true) }?.get("package") as String?
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            throw DeviceCallException("nothing on this device can open that")
        } catch (e: SecurityException) {
            throw DeviceCallException("Android did not allow the start: ${e.message}")
        }
    }
}
