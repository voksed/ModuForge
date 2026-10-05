package dev.moduforge.sandbox

import dev.moduforge.core.module.InstallResult
import dev.moduforge.core.module.ModuleManager
import dev.moduforge.core.module.ModuleRegistry
import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.core.pkg.ModulePackageVerifier
import dev.moduforge.core.pkg.PackageCheck
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * On-disk storage of module packages. A package is never installed into the OS or
 * unpacked; the sandbox reads it through a descriptor.
 */
class ModulePackageStore(private val root: File) {

    /** A package copied into the store but not yet assigned to a module. */
    class Staged internal constructor(internal val file: File)

    fun packageFile(moduleId: String): File = File(File(root, moduleId), PACKAGE_NAME)

    /** Copies a package into staging. Returns null when it cannot be read or exceeds [maxBytes]. */
    fun stage(input: InputStream, maxBytes: Long = ModulePackageFormat.MAX_UNPACKED_BYTES): Staged? {
        val staging = File(root, STAGING_DIR).apply { mkdirs() }
        val file = File.createTempFile("package", ".tmp", staging)
        return try {
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("package is too large")
                    output.write(buffer, 0, read)
                }
            }
            Staged(file)
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    /** Moves a staged package into place for [moduleId], replacing any leftover and making it read-only. */
    fun commit(staged: Staged, moduleId: String): Boolean {
        val target = packageFile(moduleId)
        target.parentFile?.mkdirs()
        target.delete()
        return staged.file.renameTo(target) && target.setReadOnly()
    }

    fun discard(staged: Staged) {
        staged.file.delete()
    }

    fun delete(moduleId: String) {
        File(root, moduleId).deleteRecursively()
    }

    /** Removes packages abandoned in staging, e.g. by a host that died during an import. */
    fun clearStaging() {
        File(root, STAGING_DIR).deleteRecursively()
    }

    private companion object {
        const val PACKAGE_NAME = "module.pkg"
        const val STAGING_DIR = ".staging"
    }
}

/** Outcome of checking a package the user picked, before anything is installed. */
sealed interface PackageInspection {
    /**
     * The package is intact and may be offered for installation.
     *
     * @property signer hex SHA-256 of the author's public key.
     * @property installedVersion version this package would replace, or null for a new module.
     */
    class Ready internal constructor(
        val manifest: ModuleManifest,
        val signer: String,
        val installedVersion: String?,
        internal val manifestJson: String,
        internal val staged: ModulePackageStore.Staged,
    ) : PackageInspection

    data class Rejected(val reason: String) : PackageInspection
}

/** Installs and removes module packages, keeping the package store and the registry consistent. */
class ModuleInstaller(
    private val store: ModulePackageStore,
    private val registry: ModuleRegistry,
    private val manager: ModuleManager,
    private val storage: ModuleStorage,
) {
    /**
     * Verifies a `.mfrg` package from an untrusted source. Nothing is installed and no module code
     * runs; a [PackageInspection.Ready] result must be passed to [install] or [discard].
     */
    suspend fun inspect(open: () -> InputStream): PackageInspection = withContext(Dispatchers.IO) {
        val staged = try {
            open().use(store::stage)
        } catch (e: IOException) {
            null
        } ?: return@withContext reject(null, "file could not be read or is too large")

        when (val check = ModulePackageVerifier.verify(staged.file)) {
            is PackageCheck.Invalid -> {
                store.discard(staged)
                reject(null, check.reason)
            }
            is PackageCheck.Valid -> {
                val installed = registry.find(check.manifest.id)
                val conflict = when {
                    installed == null -> null
                    installed.signer == null -> "a module bundled with the host cannot be replaced from a file"
                    installed.signer != check.signer -> "the package is signed by a different key than the installed module"
                    else -> null
                }
                if (conflict != null) {
                    store.discard(staged)
                    reject(check.manifest.id, conflict)
                } else {
                    PackageInspection.Ready(check.manifest, check.signer, installed?.manifest?.version, check.manifestJson, staged)
                }
            }
        }
    }

    /** Installs an inspected package after the user reviewed it, or updates the installed module with it. */
    suspend fun install(inspected: PackageInspection.Ready): InstallResult = withContext(Dispatchers.IO) {
        if (registry.find(inspected.manifest.id) == null) {
            return@withContext place(inspected.staged, inspected.manifest, inspected.manifestJson, inspected.signer)
        }
        val result = manager.update(inspected.manifestJson, inspected.signer) {
            store.commit(inspected.staged, inspected.manifest.id)
        }
        if (result is InstallResult.Rejected) store.discard(inspected.staged)
        result
    }

    suspend fun discard(inspected: PackageInspection.Ready) = withContext(Dispatchers.IO) {
        store.discard(inspected.staged)
    }

    /**
     * Installs a package shipped inside the host, which is covered by the host's own signature.
     * Accepts the `.mfrg` layout and the APK layout produced by module Gradle projects.
     */
    suspend fun installTrusted(open: () -> InputStream): InstallResult = withContext(Dispatchers.IO) {
        val staged = open().use(store::stage)
            ?: return@withContext InstallResult.Rejected(listOf("not a module package"))
        val manifestJson = readManifest(staged.file)
        val manifest = manifestJson?.let { (ModuleManifests.parse(it) as? ManifestResult.Valid)?.manifest }
        if (manifestJson == null || manifest == null) {
            store.discard(staged)
            // The manager produces and audits the precise rejection.
            return@withContext manager.install(manifestJson.orEmpty())
        }
        place(staged, manifest, manifestJson, signer = null)
    }

    suspend fun uninstall(moduleId: String): Boolean {
        if (!manager.uninstall(moduleId)) return false
        withContext(Dispatchers.IO) {
            store.delete(moduleId)
            storage.deleteAll(moduleId)
        }
        return true
    }

    private suspend fun place(
        staged: ModulePackageStore.Staged,
        manifest: ModuleManifest,
        manifestJson: String,
        signer: String?,
    ): InstallResult = withContext(Dispatchers.IO) {
        if (registry.find(manifest.id) != null) {
            store.discard(staged)
            return@withContext manager.install(manifestJson, signer)
        }
        if (!store.commit(staged, manifest.id)) {
            store.discard(staged)
            return@withContext InstallResult.Rejected(listOf("module package could not be stored"))
        }
        val result = manager.install(manifestJson, signer)
        if (result is InstallResult.Rejected) store.delete(manifest.id)
        result
    }

    private suspend fun reject(moduleId: String?, reason: String): PackageInspection.Rejected {
        manager.reportRejected(moduleId, reason)
        return PackageInspection.Rejected(reason)
    }

    private fun readManifest(file: File): String? = try {
        ZipFile(file).use { zip ->
            val entry = (zip.getEntry(ModulePackageFormat.MANIFEST) ?: zip.getEntry(APK_MANIFEST_ENTRY))
                ?.takeIf { it.size in 1..ModulePackageFormat.MAX_MANIFEST_BYTES }
            entry?.let { zip.getInputStream(it).use { stream -> stream.readBytes().decodeToString() } }
        }
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val APK_MANIFEST_ENTRY = "assets/moduforge.json"
    }
}
