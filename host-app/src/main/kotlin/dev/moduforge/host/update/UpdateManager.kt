package dev.moduforge.host.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.moduforge.core.update.AppUpdate
import dev.moduforge.core.update.AppUpdates
import dev.moduforge.host.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Where the update flow stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState

    /** @property progress 0..1, or -1 when the size is unknown. */
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState

    /** The APK is on the device, verified, and waits for the user to start the installation. */
    data class Downloaded(val update: AppUpdate) : UpdateState

    data class Failed(val reason: Reason, val detail: String? = null) : UpdateState

    enum class Reason { NETWORK, DOWNLOAD, WRONG_APP, INSTALL_NOT_ALLOWED }
}

/**
 * Finds a newer release of the app on GitHub, downloads its APK and hands it to the system
 * installer. This is the only place where the host itself connects to the network, and it does
 * so only when the user asks, or when the user switched on the check at startup.
 *
 * The APK is verified before the installer is started: it must be this very app (same package)
 * signed by the same key as the installed one. Android enforces the same on installation; the
 * check here only spares the user a confusing system error.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _checkOnStart = MutableStateFlow(preferences.getBoolean(KEY_CHECK_ON_START, false))

    /** Look for an update every time the app starts. Off until the user turns it on. */
    val checkOnStart: StateFlow<Boolean> = _checkOnStart.asStateFlow()

    private val _announced = MutableStateFlow<AppUpdate?>(null)

    /** An update found by the check at startup that the user has not been told about yet. */
    val announced: StateFlow<AppUpdate?> = _announced.asStateFlow()

    private var job: Job? = null
    private var downloaded: File? = null

    fun setCheckOnStart(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_CHECK_ON_START, enabled) }
        _checkOnStart.value = enabled
    }

    fun dismissAnnouncement() {
        _announced.value = null
    }

    /** Called once when the app starts. */
    fun checkAtStartup() {
        if (_checkOnStart.value) check(announce = true)
    }

    fun check(announce: Boolean = false) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            val result = try {
                val update = withContext(Dispatchers.IO) { fetch() }
                if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
            } catch (e: IOException) {
                UpdateState.Failed(UpdateState.Reason.NETWORK, e.message)
            }
            // A check made silently at startup says nothing when there is nothing to say.
            if (announce && result !is UpdateState.Available) _state.value = UpdateState.Idle else _state.value = result
            if (announce && result is UpdateState.Available) _announced.value = result.update
        }
    }

    fun download() {
        val update = (_state.value as? UpdateState.Available)?.update ?: return
        if (job?.isActive == true) return
        _state.value = UpdateState.Downloading(update, 0f)
        job = scope.launch {
            _state.value = try {
                val file = withContext(Dispatchers.IO) { fetchApk(update) }
                downloaded = file
                if (isOurs(file)) UpdateState.Downloaded(update) else {
                    file.delete()
                    UpdateState.Failed(UpdateState.Reason.WRONG_APP)
                }
            } catch (e: IOException) {
                UpdateState.Failed(UpdateState.Reason.DOWNLOAD, e.message)
            }
        }
    }

    /** Starts the system installer, first sending the user to the setting that allows it when needed. */
    fun install() {
        val state = _state.value as? UpdateState.Downloaded ?: return
        val file = downloaded?.takeIf { it.isFile } ?: run {
            _state.value = UpdateState.Available(state.update)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /** The newest release when it is newer than the running app. @throws IOException when GitHub cannot be reached. */
    private fun fetch(): AppUpdate? {
        val connection = open(AppUpdates.LATEST_RELEASE_URL).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            val code = connection.responseCode
            // No release published yet is an ordinary state, not a failure.
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = connection.inputStream.use { it.readBytes(MAX_RELEASE_JSON_BYTES) }.decodeToString()
            return AppUpdates.parse(body, BuildConfig.VERSION_NAME, Locale.getDefault().language)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchApk(update: AppUpdate): File {
        val directory = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(directory, "ModuForge-${update.version}.apk")
        val connection = open(update.downloadUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            // A redirect must not lead away from GitHub.
            if (!AppUpdates.isDownloadAddress(connection.url.toString())) throw IOException("unexpected address")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > AppUpdates.MAX_APK_BYTES) throw IOException("the file is too large")
                        output.write(buffer, 0, read)
                        _state.update { current ->
                            if (current is UpdateState.Downloading) current.copy(progress = if (total > 0) done.toFloat() / total else -1f) else current
                        }
                    }
                }
            }
            return target
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** True when [apk] is this app, signed with the key of the installed copy. */
    private fun isOurs(apk: File): Boolean = try {
        val packages = context.packageManager
        val archive = packages.getPackageArchiveInfo(apk.path, signatureFlag())
        val installed = packages.getPackageInfo(context.packageName, signatureFlag())
        archive != null && archive.packageName == context.packageName &&
            signers(archive).isNotEmpty() && signers(archive) == signers(installed)
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun signatureFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** SHA-256 of every certificate the package is signed with. */
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun open(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", "ModuForge/${BuildConfig.VERSION_NAME}")
        return connection
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw IOException("the answer is too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val KEY_CHECK_ON_START = "check_on_start"
        const val TIMEOUT_MILLIS = 15_000
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024
    }
}
