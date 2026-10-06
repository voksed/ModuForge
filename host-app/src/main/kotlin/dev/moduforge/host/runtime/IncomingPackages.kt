package dev.moduforge.host.runtime

import android.content.Intent
import android.net.Uri
import dev.moduforge.core.authoring.InstallLink
import dev.moduforge.core.authoring.InstallLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** Something the user opened with the app from outside of it. */
sealed interface IncomingPackage {
    /** A file handed over by a file manager, a browser download or a messenger. */
    data class File(val uri: Uri) : IncomingPackage

    /** A `moduforge://install` link. Nothing is downloaded until the user agrees. */
    data class Link(val link: InstallLink) : IncomingPackage

    /** A link the app was opened with but cannot use. */
    data object Unusable : IncomingPackage
}

/** Hands what the app was opened with to the module list, which puts it up for review. */
@Singleton
class IncomingPackages @Inject constructor() {
    private val _pending = MutableStateFlow<IncomingPackage?>(null)

    /** The newest item not taken yet. */
    val pending: StateFlow<IncomingPackage?> = _pending

    /** Records what [intent] carries; other intents are ignored. */
    fun offer(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        _pending.value = when (data.scheme?.lowercase()) {
            InstallLinks.SCHEME -> InstallLinks.parse(data.toString())?.let(IncomingPackage::Link) ?: IncomingPackage.Unusable
            "content", "file" -> IncomingPackage.File(data)
            else -> return
        }
    }

    fun take(): IncomingPackage? = _pending.getAndUpdate { null }
}
