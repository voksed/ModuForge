package dev.moduforge.core.pkg

import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Layout of a `.mfrg` module package (a ZIP archive):
 *
 * ```
 * moduforge.json      manifest
 * code/...            what the runtime executes: classes*.dex or script sources
 * data/...            read-only files shipped with the module
 * META-INF/MFRG.SIG   author's signature over every other entry
 * ```
 */
object ModulePackageFormat {
    const val EXTENSION = "mfrg"
    const val MANIFEST = "moduforge.json"
    const val SIGNATURE = "META-INF/MFRG.SIG"
    const val CODE_PREFIX = "code/"
    const val DATA_PREFIX = "data/"

    const val MAX_ENTRIES = 10_000
    const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024

    internal const val ALGORITHM = "SHA256withECDSA"

    fun isContentEntry(name: String): Boolean =
        name == MANIFEST || name.startsWith(CODE_PREFIX) || name.startsWith(DATA_PREFIX)
}

/** Author's signing key. The private half never leaves the author's machine. */
class SigningKey(val keyPair: KeyPair) {

    /** Hex SHA-256 of the public key; identifies the author to users. */
    val fingerprint: String get() = fingerprintOf(keyPair.public.encoded)

    fun encode(): String = json.encodeToString(
        Stored.serializer(),
        Stored(
            privateKey = Base64.getEncoder().encodeToString(keyPair.private.encoded),
            publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        ),
    )

    @Serializable
    private class Stored(val algorithm: String = "EC-P256", val privateKey: String, val publicKey: String)

    companion object {
        private val json = Json { prettyPrint = true }

        fun generate(): SigningKey {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return SigningKey(generator.generateKeyPair())
        }

        /** @throws IllegalArgumentException when [text] is not a key produced by [encode]. */
        fun decode(text: String): SigningKey = try {
            val stored = json.decodeFromString(Stored.serializer(), text)
            val factory = KeyFactory.getInstance("EC")
            val private: PrivateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.privateKey)))
            val public: PublicKey = factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKey)))
            SigningKey(KeyPair(public, private))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("not a signing key", e)
        }

        internal fun fingerprintOf(encodedPublicKey: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encodedPublicKey).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
internal class SignatureBlock(val algorithm: String, val publicKey: String, val signature: String)

/** Digest binding every content entry's name to its bytes; entries are fed in name order. */
internal class PackageDigest {
    private val total = MessageDigest.getInstance("SHA-256")

    fun add(name: String, content: InputStream): Long {
        val entry = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val read = content.read(buffer)
            if (read < 0) break
            entry.update(buffer, 0, read)
            size += read
        }
        val nameBytes = name.toByteArray()
        total.update(ByteBuffer.allocate(4).putInt(nameBytes.size).array())
        total.update(nameBytes)
        total.update(entry.digest())
        return size
    }

    fun finish(): ByteArray = total.digest()
}

/** Builds signed packages. Output is deterministic for the same inputs apart from the signature bytes. */
object ModulePackageWriter {

    /**
     * @param files package path (under `code/` or `data/`) to content.
     * @throws IllegalArgumentException when the manifest or a path is invalid, or the entry script is missing.
     */
    fun write(output: OutputStream, manifestJson: String, files: Map<String, ByteArray>, key: SigningKey) {
        val manifest = when (val parsed = ModuleManifests.parse(manifestJson)) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Invalid -> throw IllegalArgumentException("invalid manifest: ${parsed.problems.joinToString("; ")}")
        }
        files.keys.forEach { name ->
            require(name != ModulePackageFormat.MANIFEST && ModulePackageFormat.isContentEntry(name) && ModuleManifests.isRelativePath(name)) {
                "not a package path: $name"
            }
        }
        requireEntryPoint(manifest, files.keys)

        val content = (files + (ModulePackageFormat.MANIFEST to manifestJson.toByteArray())).toSortedMap()
        val digest = PackageDigest()
        content.forEach { (name, bytes) -> digest.add(name, bytes.inputStream()) }
        val signature = Signature.getInstance(ModulePackageFormat.ALGORITHM).run {
            initSign(key.keyPair.private)
            update(digest.finish())
            sign()
        }
        val block = SignatureBlock(
            algorithm = ModulePackageFormat.ALGORITHM,
            publicKey = Base64.getEncoder().encodeToString(key.keyPair.public.encoded),
            signature = Base64.getEncoder().encodeToString(signature),
        )

        ZipOutputStream(output).use { zip ->
            val all = content + (ModulePackageFormat.SIGNATURE to Json.encodeToString(SignatureBlock.serializer(), block).toByteArray())
            all.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun requireEntryPoint(manifest: ModuleManifest, names: Set<String>) {
        val code = names.filter { it.startsWith(ModulePackageFormat.CODE_PREFIX) }
        when (manifest.runtime) {
            dev.moduforge.sdk.ModuleRuntimeKind.DEX ->
                require(code.any { it.endsWith(".dex") }) { "a dex module needs code/classes.dex" }
            else ->
                require(ModulePackageFormat.CODE_PREFIX + manifest.entry in names) { "entry script code/${manifest.entry} is missing" }
        }
    }
}

sealed interface PackageCheck {
    /** @property signer hex SHA-256 of the author's public key. */
    data class Valid(val manifest: ModuleManifest, val manifestJson: String, val signer: String) : PackageCheck

    data class Invalid(val reason: String) : PackageCheck
}

/** Checks the structure and the signature of a package before anything in it is used. */
object ModulePackageVerifier {

    fun verify(file: File): PackageCheck = try {
        ZipFile(file).use(::verify)
    } catch (e: java.io.IOException) {
        PackageCheck.Invalid("not a module package")
    }

    private fun verify(zip: ZipFile): PackageCheck {
        val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
        if (entries.size > ModulePackageFormat.MAX_ENTRIES) return PackageCheck.Invalid("too many files")
        if (entries.map { it.name }.toSet().size != entries.size) return PackageCheck.Invalid("duplicate file names")
        entries.firstOrNull { it.name != ModulePackageFormat.SIGNATURE && !(ModulePackageFormat.isContentEntry(it.name) && ModuleManifests.isRelativePath(it.name)) }
            ?.let { return PackageCheck.Invalid("unexpected file: ${it.name.take(80)}") }

        val signatureEntry = entries.firstOrNull { it.name == ModulePackageFormat.SIGNATURE }
            ?: return PackageCheck.Invalid("package is not signed")
        val block = try {
            Json.decodeFromString(SignatureBlock.serializer(), zip.readBounded(signatureEntry, ModulePackageFormat.MAX_MANIFEST_BYTES))
        } catch (e: SerializationException) {
            return PackageCheck.Invalid("malformed signature")
        }
        if (block.algorithm != ModulePackageFormat.ALGORITHM) return PackageCheck.Invalid("unsupported signature algorithm")

        val digest = PackageDigest()
        var total = 0L
        entries.filter { it.name != ModulePackageFormat.SIGNATURE }.sortedBy { it.name }.forEach { entry ->
            total += zip.getInputStream(entry).use { digest.add(entry.name, LimitedInputStream(it, ModulePackageFormat.MAX_UNPACKED_BYTES - total)) }
        }

        val publicKeyBytes: ByteArray
        val valid = try {
            publicKeyBytes = Base64.getDecoder().decode(block.publicKey)
            Signature.getInstance(ModulePackageFormat.ALGORITHM).run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes)))
                update(digest.finish())
                verify(Base64.getDecoder().decode(block.signature))
            }
        } catch (e: GeneralSecurityException) {
            return PackageCheck.Invalid("malformed signature")
        } catch (e: IllegalArgumentException) {
            return PackageCheck.Invalid("malformed signature")
        }
        if (!valid) return PackageCheck.Invalid("signature does not match the package contents")

        val manifestEntry = entries.firstOrNull { it.name == ModulePackageFormat.MANIFEST }
            ?: return PackageCheck.Invalid("manifest is missing")
        val manifestJson = zip.readBounded(manifestEntry, ModulePackageFormat.MAX_MANIFEST_BYTES)
        return when (val parsed = ModuleManifests.parse(manifestJson)) {
            is ManifestResult.Valid -> PackageCheck.Valid(parsed.manifest, manifestJson, SigningKey.fingerprintOf(publicKeyBytes))
            is ManifestResult.Invalid -> PackageCheck.Invalid(parsed.problems.joinToString("; "))
        }
    }

    private fun ZipFile.readBounded(entry: ZipEntry, limit: Int): String =
        getInputStream(entry).use { LimitedInputStream(it, limit.toLong()).readBytes().decodeToString() }

    /** Fails instead of reading past [remaining] bytes, so that declared sizes need not be trusted. */
    private class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val value = source.read()
            if (value >= 0) consume(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) consume(read)
            return read
        }

        private fun consume(count: Int) {
            remaining -= count
            if (remaining < 0) throw java.io.IOException("package is too large")
        }
    }
}
