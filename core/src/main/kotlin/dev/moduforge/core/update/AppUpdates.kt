package dev.moduforge.core.update

import dev.moduforge.sdk.SemVer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * A newer version of the app found in the releases of the project.
 *
 * @property version version without the leading `v` of the tag.
 * @property downloadUrl HTTPS address of the APK.
 * @property sizeBytes size announced by the release; 0 when unknown.
 * @property notes release notes in the language asked for.
 */
data class AppUpdate(val version: String, val downloadUrl: String, val sizeBytes: Long, val notes: String)

/**
 * Reads the answer of the GitHub "latest release" endpoint and decides whether it offers
 * something newer than the running app. No network access happens here.
 */
object AppUpdates {
    /** Address of the endpoint that describes the newest published (non-draft, non-pre-release) release. */
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/voksed/ModuForge/releases/latest"

    /** Largest APK the app agrees to download. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    private val DOWNLOAD_HOSTS = listOf("github.com", "githubusercontent.com")

    /**
     * @param json body of the release answer.
     * @param currentVersion version of the running app.
     * @param language two-letter language of the app, for the release notes.
     * @param full true for the full edition, which updates to the APK with `full` in its name;
     * the standard edition takes an APK without it.
     * @return the update, or null when the release is not newer, malformed or carries no usable APK.
     */
    fun parse(json: String, currentVersion: String, language: String, full: Boolean = false): AppUpdate? {
        val release = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (release.text("draft") == "true" || release.text("prerelease") == "true") return null

        val version = release.text("tag_name")?.trim()?.removePrefix("v")?.removePrefix("V")?.let(SemVer::parseOrNull) ?: return null
        val current = SemVer.parseOrNull(currentVersion) ?: return null
        if (version <= current) return null

        val apk = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { asset ->
            val name = asset.text("name")?.lowercase() ?: return@firstNotNullOfOrNull null
            val url = asset.text("browser_download_url") ?: return@firstNotNullOfOrNull null
            if (name.endsWith(".apk") && ("full" in name) == full && isDownloadAddress(url)) url to ((asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L) else null
        } ?: return null
        if (apk.second > MAX_APK_BYTES) return null

        return AppUpdate(version.toString(), apk.first, apk.second, notesFor(release.text("body").orEmpty(), language))
    }

    /** True for an HTTPS address on GitHub, where releases are served from. */
    fun isDownloadAddress(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        "https".equals(uri.scheme, ignoreCase = true) && uri.userInfo == null && host != null &&
            DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
    } catch (e: URISyntaxException) {
        false
    }

    /**
     * Section of the release notes for [language]. A body may carry several languages, each
     * introduced by a line holding only a marker such as `[[en]]` or `[[ru]]`; English is the
     * fallback, and a body without markers is returned whole.
     */
    fun notesFor(body: String, language: String): String {
        val markers = Regex("""(?m)^[ \t]*\[\[([A-Za-z]{2})]][ \t]*$""").findAll(body).toList()
        if (markers.isEmpty()) return body.trim()
        val sections = LinkedHashMap<String, String>()
        markers.forEachIndexed { index, marker ->
            val end = markers.getOrNull(index + 1)?.range?.first ?: body.length
            sections[marker.groupValues[1].lowercase()] = body.substring(marker.range.last + 1, end).trim()
        }
        return sections[language.lowercase()] ?: sections["en"] ?: sections.values.first()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
