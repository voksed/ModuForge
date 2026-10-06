package dev.moduforge.core.authoring

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where to download a module package from, as carried by a `moduforge://install` link.
 *
 * @property url HTTPS address of the `.mfrg` file.
 * @property signer fingerprint of the key the package must be signed with, when the link names one.
 */
data class InstallLink(val url: String, val signer: String?)

/**
 * Links that install a module: `moduforge://install?url=<https address>&signer=<fingerprint>`.
 *
 * An author publishes such a link, as text or as a QR code, next to the package. With the
 * signer in it the host itself checks that the downloaded package comes from that key, so
 * nobody has to compare fingerprints by eye.
 */
object InstallLinks {
    const val SCHEME = "moduforge"
    const val HOST = "install"
    private const val FINGERPRINT_LENGTH = 64

    /** @return null when [link] is not a well-formed install link. */
    fun parse(link: String): InstallLink? {
        val uri = try {
            URI(link.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            try {
                name to URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toMap()

        val url = parameters["url"]?.takeIf(::isDownloadAddress) ?: return null
        val signer = parameters["signer"]?.let { normalizeFingerprint(it) ?: return null }
        return InstallLink(url, signer)
    }

    /** @throws IllegalArgumentException when [url] is not an HTTPS address or [signer] is not a fingerprint. */
    fun create(url: String, signer: String?): String {
        require(isDownloadAddress(url)) { "the address must start with https://" }
        val fingerprint = signer?.let { requireNotNull(normalizeFingerprint(it)) { "not a key fingerprint: $it" } }
        return "$SCHEME://$HOST?url=" + URLEncoder.encode(url, "UTF-8") + (fingerprint?.let { "&signer=$it" } ?: "")
    }

    /** True when [actual] is the key the link named. */
    fun signerMatches(link: InstallLink, actual: String): Boolean =
        link.signer == null || link.signer == normalizeFingerprint(actual)

    private fun isDownloadAddress(url: String): Boolean = try {
        val uri = URI(url)
        "https".equals(uri.scheme, ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null
    } catch (e: URISyntaxException) {
        false
    }

    /** Lower-case hex without separators, or null when [text] is not a SHA-256 fingerprint. */
    private fun normalizeFingerprint(text: String): String? =
        text.lowercase().filterNot { it == ':' || it == ' ' || it == '-' }
            .takeIf { it.length == FINGERPRINT_LENGTH && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
}
