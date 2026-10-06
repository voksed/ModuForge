package dev.moduforge.packer

import dev.moduforge.core.dev.DevPush
import dev.moduforge.core.dev.DevPushClient
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Where `mfrg push` remembers the phone it talks to. Replaced in tests. */
internal var pushSettingsFile: File = File(System.getProperty("user.home"), ".moduforge/push.properties")

/** Value of `--host` that means "the phone on the USB cable". */
private const val USB = "usb"
private const val LOOPBACK = "127.0.0.1"

/**
 * Packs the module in [dir], sends it to the app in developer mode, which installs it and
 * restarts the module, and then prints the module's output until interrupted.
 *
 * @param forwardUsb makes the phone's port reachable on this computer; returns a problem or null.
 */
internal fun push(dir: File, options: Options, print: (String) -> Unit, forwardUsb: (Int) -> String? = ::adbForward): String {
    val saved = Properties().apply {
        if (pushSettingsFile.isFile) pushSettingsFile.inputStream().use(::load)
    }
    val token = options.optional("token") ?: System.getenv("MODUFORGE_PUSH_TOKEN") ?: saved.getProperty("token")
        ?: throw UsageError(
            "no pairing token yet. In the app open Settings, turn on developer mode and run the command shown there " +
                "(mfrg push --token XXXX-XXXX-XXXX-XXXX); the token is remembered after that.",
        )
    val host = options.optional("host") ?: saved.getProperty("host") ?: USB
    val port = (options.optional("port") ?: saved.getProperty("port"))?.let {
        it.toIntOrNull()?.takeIf { number -> number in 1..65535 } ?: throw UsageError("invalid port: $it")
    } ?: DevPush.DEFAULT_PORT

    val packageFile = File.createTempFile("push", ".tmp")
    val packageBytes = try {
        pack(dir, options, packageFile)
        packageFile.readBytes()
    } finally {
        packageFile.delete()
    }

    val overUsb = host.equals(USB, ignoreCase = true)
    if (overUsb) {
        forwardUsb(port)?.let { problem ->
            throw UsageError("$problem\nConnect the phone with USB debugging on, or use Wi-Fi: mfrg push --host <phone address>")
        }
    }
    val address = if (overUsb) LOOPBACK else host
    val follow = !options.switch("no-follow")
    val pending = StringBuilder()
    val reply = try {
        DevPushClient.push(
            address, port, token, packageBytes,
            onReply = { if (it.ok && follow) print("${it.message}\n--- output, Ctrl+C to stop watching (the module keeps running) ---") },
            onOutput = if (!follow) null else { chunk ->
                // Output arrives in arbitrary pieces; whole lines are passed on.
                pending.append(chunk)
                while (true) {
                    val end = pending.indexOf("\n")
                    if (end < 0) break
                    print(pending.substring(0, end))
                    pending.delete(0, end + 1)
                }
            },
        )
    } catch (e: IOException) {
        throw UsageError(
            "cannot reach the app at ${if (overUsb) "the USB-connected phone" else host}, port $port: ${e.message}\n" +
                "Developer mode must be on (Settings in the app) and the app open" +
                if (overUsb) "." else ", with \"Accept over Wi-Fi\" enabled and both devices in one network.",
        )
    }
    if (!reply.ok) throw UsageError("the app refused the package: ${reply.message}")

    // Only what worked is remembered.
    saved.setProperty("token", token)
    saved.setProperty("host", host)
    saved.setProperty("port", port.toString())
    try {
        pushSettingsFile.absoluteFile.parentFile?.mkdirs()
        pushSettingsFile.outputStream().use { saved.store(it, "mfrg push") }
    } catch (e: IOException) {
        // Not fatal: the options have to be given again next time.
    }
    return if (follow) pending.toString() else reply.message
}

/** Runs `adb forward` so that the phone's [port] answers on this computer. */
private fun adbForward(port: Int): String? {
    val names = listOfNotNull(
        "adb",
        (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { File(it, "platform-tools/adb").path },
    )
    for (adb in names) {
        try {
            val process = ProcessBuilder(adb, "forward", "tcp:$port", "tcp:$port").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "adb did not answer"
            }
            return if (process.exitValue() == 0) null else "adb: ${output.ifEmpty { "no device" }}"
        } catch (e: IOException) {
            continue
        }
    }
    return "adb was not found (it comes with Android platform-tools)"
}
