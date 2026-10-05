package dev.moduforge.sandbox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.moduforge.sdk.ModuleManifests
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encryption of data at rest. */
interface StorageCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException when [data] was not produced by [encrypt] or was altered. */
    fun decrypt(data: ByteArray): ByteArray
}

/** AES-256-GCM with a key that is generated inside the Android Keystore and never leaves it. */
class KeystoreStorageCipher(private val alias: String = "moduforge.module-storage") : StorageCipher {

    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Private files of modules, held by the host because a sandbox has no file system of its own.
 * Every file is encrypted; a module can address only paths under its own directory.
 */
class ModuleStorage(private val root: File, private val cipher: StorageCipher) {

    /** @throws IllegalArgumentException when [path] is not a relative path. */
    fun read(moduleId: String, path: String): ByteArray? {
        val file = resolve(moduleId, path)
        return if (file.isFile) cipher.decrypt(file.readBytes()) else null
    }

    /** @throws IllegalArgumentException when the path is invalid or a size limit is exceeded. */
    fun write(moduleId: String, path: String, data: ByteArray) {
        val file = resolve(moduleId, path)
        require(data.size <= MAX_FILE_BYTES) { "file exceeds $MAX_FILE_BYTES bytes" }
        val others = files(moduleId).filter { it != file }
        require(others.size < MAX_FILES) { "module storage holds too many files" }
        require(others.sumOf { it.length() } + data.size <= MAX_TOTAL_BYTES) { "module storage exceeds $MAX_TOTAL_BYTES bytes" }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + TEMP_SUFFIX)
        temporary.writeBytes(cipher.encrypt(data))
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalArgumentException("path collides with an existing directory")
        }
    }

    fun delete(moduleId: String, path: String): Boolean = resolve(moduleId, path).let { it.isFile && it.delete() }

    fun list(moduleId: String): List<String> {
        val directory = directory(moduleId)
        return files(moduleId).map { it.relativeTo(directory).invariantSeparatorsPath }.sorted()
    }

    fun deleteAll(moduleId: String) {
        directory(moduleId).deleteRecursively()
    }

    private fun directory(moduleId: String) = File(root, moduleId)

    private fun files(moduleId: String): List<File> =
        directory(moduleId).walkTopDown().filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }.toList()

    private fun resolve(moduleId: String, path: String): File {
        require(ModuleManifests.isRelativePath(path) && path.length <= MAX_PATH_LENGTH && !path.endsWith(TEMP_SUFFIX)) {
            "invalid storage path"
        }
        return File(directory(moduleId), path)
    }

    companion object {
        /** Bounded by what one IPC transaction carries. */
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_FILES = 2_000
        private const val MAX_PATH_LENGTH = 255
        private const val TEMP_SUFFIX = ".mftmp"
    }
}
