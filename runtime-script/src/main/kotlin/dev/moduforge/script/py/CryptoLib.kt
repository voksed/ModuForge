package dev.moduforge.script.py

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Binary building blocks that a protocol client written in Python needs and that would be far too slow in
 * the interpreter: compression, `struct`, HMAC, AES in the modes MTProto uses (IGE, CTR) and the common
 * ones (CBC, ECB, GCM), PBKDF2. Everything works on `bytes`; no file or network access.
 */
internal object CryptoLib {
    private val random = SecureRandom()

    fun create(name: String): PyModule? = when (name) {
        "zlib" -> zlib()
        "gzip" -> gzip()
        "struct" -> struct()
        "binascii" -> binascii()
        "hmac" -> hmac()
        "secrets" -> secrets()
        "os" -> os()
        "crypto" -> crypto()
        else -> null
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun fn(name: String, impl: (Args) -> Any?): Pair<String, Any?> = name to PyBuiltin(name) { a, k -> impl(Args(a, k, name)) }

    private fun module(name: String, vararg entries: Pair<String, Any?>) = PyModule(name, linkedMapOf(*entries))

    private fun bytesOf(v: Any?): ByteArray = when (v) {
        is PyBytes -> v.data
        is String -> v.toByteArray(Charsets.UTF_8)
        null, PyNone -> throw Py.error("TypeError", "a bytes-like object is required, not None")
        else -> {
            if (v is PyInstance || v is PyList) PyBytes(Py.toList(v).map { Py.toInt(it).toByte() }.toByteArray()).data
            else throw Py.error("TypeError", "a bytes-like object is required, not '${Py.typeName(v)}'")
        }
    }

    private fun bytes(data: ByteArray) = PyBytes(data)

    private fun fail(kind: String, message: String): Nothing = throw Py.error(kind, message)

    // --- zlib, gzip -----------------------------------------------------------------------------

    private fun deflate(data: ByteArray, level: Int, nowrap: Boolean): ByteArray {
        val deflater = Deflater(if (level < 0) Deflater.DEFAULT_COMPRESSION else level.coerceAtMost(9), nowrap)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) fail("ValueError", "incomplete or truncated stream")
                out.write(buffer, 0, n)
                if (out.size() > MAX_OUTPUT) fail("ValueError", "decompressed data is too large")
            }
        } catch (e: java.util.zip.DataFormatException) {
            fail("ValueError", "invalid compressed data: ${e.message}")
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray = try {
        GZIPInputStream(data.inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_OUTPUT) fail("ValueError", "decompressed data is too large")
            }
            out.toByteArray()
        }
    } catch (e: java.io.IOException) {
        fail("ValueError", "invalid gzip data: ${e.message}")
    }

    private fun zlib() = module(
        "zlib",
        fn("compress") { a -> bytes(deflate(bytesOf(a.req(0, "data")), a.long(1, "level", -1).toInt(), nowrap = false)) },
        fn("decompress") { a ->
            val data = bytesOf(a.req(0, "data"))
            when (val wbits = a.long(1, "wbits", 15).toInt()) {
                in 8..15 -> bytes(inflate(data, nowrap = false))
                in -15..-8 -> bytes(inflate(data, nowrap = true))
                in 24..31, in 40..47 -> bytes(gunzip(data))
                else -> fail("ValueError", "invalid window size $wbits")
            }
        },
        fn("crc32") { a -> CRC32().also { c -> c.update(bytesOf(a.req(0, "data"))) }.value },
        fn("adler32") { a -> Adler32().also { c -> c.update(bytesOf(a.req(0, "data"))) }.value },
    )

    private fun gzip() = module(
        "gzip",
        fn("compress") { a ->
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytesOf(a.req(0, "data"))) }
            bytes(out.toByteArray())
        },
        fn("decompress") { a -> bytes(gunzip(bytesOf(a.req(0, "data")))) },
    )

    // --- struct ---------------------------------------------------------------------------------

    private class Field(val code: Char, val count: Int)

    private class Layout(val fields: List<Field>, val little: Boolean) {
        val size: Int = fields.sumOf { sizeOf(it) }
    }

    private fun sizeOf(f: Field): Int = when (f.code) {
        'x', 'c', 'b', 'B', '?' -> f.count
        's', 'p' -> f.count
        'h', 'H' -> 2 * f.count
        'i', 'I', 'l', 'L', 'f' -> 4 * f.count
        'q', 'Q', 'd' -> 8 * f.count
        else -> fail("ValueError", "bad char in struct format")
    }

    private fun layout(format: String): Layout {
        var little = true
        var text = format
        if (text.isNotEmpty() && text[0] in "<>!=@") {
            little = text[0] == '<' || text[0] == '=' || text[0] == '@'
            text = text.substring(1)
        }
        val fields = mutableListOf<Field>()
        var count = ""
        for (ch in text) {
            when {
                ch.isWhitespace() -> Unit
                ch.isDigit() -> count += ch
                else -> {
                    fields += Field(ch, count.toIntOrNull() ?: 1)
                    count = ""
                }
            }
        }
        return Layout(fields, little)
    }

    private fun putInt(out: ByteArrayOutputStream, value: BigInteger, size: Int, signed: Boolean, little: Boolean) {
        val bits = size * 8
        val limit = BigInteger.ONE.shiftLeft(bits)
        val v = if (signed) {
            val min = BigInteger.ONE.shiftLeft(bits - 1).negate()
            val max = BigInteger.ONE.shiftLeft(bits - 1) - BigInteger.ONE
            if (value < min || value > max) fail("ValueError", "argument out of range")
            if (value.signum() < 0) value + limit else value
        } else {
            if (value.signum() < 0 || value >= limit) fail("ValueError", "argument out of range")
            value
        }
        val raw = ByteArray(size)
        val big = v.toByteArray()
        for (i in 0 until size) {
            val index = big.size - 1 - i
            raw[i] = if (index >= 0) big[index] else 0
        }
        out.write(if (little) raw else raw.reversedArray())
    }

    private fun getInt(data: ByteArray, at: Int, size: Int, signed: Boolean, little: Boolean): Any? {
        val chunk = data.copyOfRange(at, at + size)
        val bigEndian = if (little) chunk.reversedArray() else chunk
        val v = BigInteger(1, bigEndian)
        val result = if (signed && bigEndian[0] < 0) v - BigInteger.ONE.shiftLeft(size * 8) else v
        return Py.norm(result)
    }

    private fun pack(format: String, values: List<Any?>): ByteArray {
        val layout = layout(format)
        val out = ByteArrayOutputStream()
        var next = 0
        fun take(): Any? = values.getOrNull(next++) ?: fail("ValueError", "pack expected more items for packing")
        for (f in layout.fields) {
            when (f.code) {
                'x' -> repeat(f.count) { out.write(0) }
                'c' -> repeat(f.count) { out.write(bytesOf(take()).also { if (it.size != 1) fail("ValueError", "char format requires a bytes object of length 1") }) }
                's', 'p' -> {
                    val raw = bytesOf(take())
                    out.write(raw.copyOf(f.count))
                }
                '?' -> repeat(f.count) { out.write(if (Py.truth(take())) 1 else 0) }
                'b' -> repeat(f.count) { putInt(out, Py.toBig(take()), 1, true, layout.little) }
                'B' -> repeat(f.count) { putInt(out, Py.toBig(take()), 1, false, layout.little) }
                'h' -> repeat(f.count) { putInt(out, Py.toBig(take()), 2, true, layout.little) }
                'H' -> repeat(f.count) { putInt(out, Py.toBig(take()), 2, false, layout.little) }
                'i', 'l' -> repeat(f.count) { putInt(out, Py.toBig(take()), 4, true, layout.little) }
                'I', 'L' -> repeat(f.count) { putInt(out, Py.toBig(take()), 4, false, layout.little) }
                'q' -> repeat(f.count) { putInt(out, Py.toBig(take()), 8, true, layout.little) }
                'Q' -> repeat(f.count) { putInt(out, Py.toBig(take()), 8, false, layout.little) }
                'f' -> repeat(f.count) { putInt(out, BigInteger.valueOf(java.lang.Float.floatToIntBits(Py.toDouble(take()).toFloat()).toLong()), 4, true, layout.little) }
                'd' -> repeat(f.count) { putInt(out, BigInteger.valueOf(java.lang.Double.doubleToLongBits(Py.toDouble(take()))), 8, true, layout.little) }
                else -> fail("ValueError", "bad char in struct format")
            }
        }
        if (next != values.size) fail("ValueError", "pack expected ${next} items for packing (got ${values.size})")
        return out.toByteArray()
    }

    private fun unpack(format: String, data: ByteArray, offset: Int): PyTuple {
        val layout = layout(format)
        if (offset < 0 || data.size - offset < layout.size) fail("ValueError", "unpack requires a buffer of ${layout.size} bytes")
        val result = mutableListOf<Any?>()
        var at = offset
        for (f in layout.fields) {
            when (f.code) {
                'x' -> at += f.count
                'c' -> repeat(f.count) { result += bytes(byteArrayOf(data[at++])) }
                's', 'p' -> {
                    result += bytes(data.copyOfRange(at, at + f.count))
                    at += f.count
                }
                '?' -> repeat(f.count) { result += (data[at++] != 0.toByte()) }
                'b', 'B', 'h', 'H', 'i', 'I', 'l', 'L', 'q', 'Q' -> {
                    val size = sizeOf(Field(f.code, 1))
                    val signed = f.code.isLowerCase()
                    repeat(f.count) {
                        result += getInt(data, at, size, signed, layout.little)
                        at += size
                    }
                }
                'f' -> repeat(f.count) {
                    result += java.lang.Float.intBitsToFloat(Py.toLong(getInt(data, at, 4, true, layout.little)).toInt()).toDouble()
                    at += 4
                }
                'd' -> repeat(f.count) {
                    result += java.lang.Double.longBitsToDouble(Py.toLong(getInt(data, at, 8, true, layout.little)))
                    at += 8
                }
                else -> fail("ValueError", "bad char in struct format")
            }
        }
        return PyTuple(result)
    }

    private fun struct() = module(
        "struct",
        fn("pack") { a -> bytes(pack(a.str(0, "format"), a.pos.drop(1))) },
        fn("unpack") { a ->
            val data = bytesOf(a.req(1, "buffer"))
            val l = layout(a.str(0, "format"))
            if (data.size != l.size) fail("ValueError", "unpack requires a buffer of ${l.size} bytes")
            unpack(a.str(0, "format"), data, 0)
        },
        fn("unpack_from") { a -> unpack(a.str(0, "format"), bytesOf(a.req(1, "buffer")), a.long(2, "offset", 0).toInt()) },
        fn("calcsize") { a -> layout(a.str(0, "format")).size.toLong() },
    ).also { it.attrs["error"] = Builtins.exceptionClass("ValueError") }

    // --- binascii, hmac, secrets, os --------------------------------------------------------------

    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String): ByteArray {
        val clean = text.trim()
        if (clean.length % 2 != 0) fail("ValueError", "Odd-length string")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: fail("ValueError", "Non-hexadecimal digit found")
        }
    }

    private fun binascii() = module(
        "binascii",
        fn("hexlify") { a -> bytes(hex(bytesOf(a.req(0, "data"))).toByteArray()) },
        fn("b2a_hex") { a -> bytes(hex(bytesOf(a.req(0, "data"))).toByteArray()) },
        fn("unhexlify") { a -> bytes(unhex(String(bytesOf(a.req(0, "hexstr"))))) },
        fn("a2b_hex") { a -> bytes(unhex(String(bytesOf(a.req(0, "hexstr"))))) },
        fn("crc32") { a -> CRC32().also { c -> c.update(bytesOf(a.req(0, "data"))) }.value },
    )

    private fun macName(digest: String): String = when (digest.lowercase().replace("-", "")) {
        "md5" -> "HmacMD5"
        "sha1" -> "HmacSHA1"
        "sha256" -> "HmacSHA256"
        "sha384" -> "HmacSHA384"
        "sha512" -> "HmacSHA512"
        else -> fail("ValueError", "unsupported hash type $digest")
    }

    private fun mac(digest: String, key: ByteArray): Mac = Mac.getInstance(macName(digest)).apply {
        init(if (key.isEmpty()) EmptyKey(algorithm) else SecretKeySpec(key, algorithm))
    }

    /** HMAC allows an empty key; the JCA does not allow an empty `SecretKeySpec`. */
    private class EmptyKey(private val algorithm: String) : javax.crypto.SecretKey {
        override fun getAlgorithm() = algorithm
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun digestName(v: Any?): String = when (v) {
        is String -> v
        is PyBuiltin -> v.name
        is PyObject -> Py.str(v.attrs["name"])
        else -> "sha256"
    }

    private fun macObject(m: Mac, digest: String): PyObject {
        val o = PyObject("hmac")
        o.attrs["name"] = "hmac-$digest"
        o.attrs["update"] = PyBuiltin("update") { a, _ -> m.update(bytesOf(a[0])); PyNone }
        o.attrs["digest"] = PyBuiltin("digest") { _, _ -> bytes((m.clone() as Mac).doFinal()) }
        o.attrs["hexdigest"] = PyBuiltin("hexdigest") { _, _ -> hex((m.clone() as Mac).doFinal()) }
        o.attrs["copy"] = PyBuiltin("copy") { _, _ -> macObject(m.clone() as Mac, digest) }
        return o
    }

    private fun hmac() = module(
        "hmac",
        fn("new") { a ->
            val digest = digestName(a.opt(2, "digestmod"))
            val m = mac(digest, bytesOf(a.req(0, "key")))
            a.opt(1, "msg")?.takeIf { it !== PyNone }?.let { m.update(bytesOf(it)) }
            macObject(m, digest)
        },
        fn("digest") { a ->
            val m = mac(digestName(a.req(2, "digest")), bytesOf(a.req(0, "key")))
            bytes(m.doFinal(bytesOf(a.req(1, "msg"))))
        },
        fn("compare_digest") { a -> MessageDigest.isEqual(bytesOf(a.req(0, "a")), bytesOf(a.req(1, "b"))) },
    )

    private fun randomBytes(count: Int): ByteArray {
        if (count < 0 || count > MAX_RANDOM) fail("ValueError", "number of random bytes must be between 0 and $MAX_RANDOM")
        return ByteArray(count).also(random::nextBytes)
    }

    private fun secrets() = module(
        "secrets",
        fn("token_bytes") { a -> bytes(randomBytes(a.long(0, "nbytes", 32).toInt())) },
        fn("token_hex") { a -> hex(randomBytes(a.long(0, "nbytes", 32).toInt())) },
        fn("randbits") { a -> Py.norm(BigInteger(a.long(0, "k").toInt(), random)) },
        fn("randbelow") { a ->
            val n = Py.toBig(a.req(0, "exclusive_upper_bound"))
            if (n.signum() <= 0) fail("ValueError", "Upper bound must be positive.")
            var v: BigInteger
            do {
                v = BigInteger(n.bitLength(), random)
            } while (v >= n)
            Py.norm(v)
        },
    )

    /** Not the operating system: only what protocol code asks for (`os.urandom`). */
    private fun os() = module(
        "os",
        fn("urandom") { a -> bytes(randomBytes(a.long(0, "size").toInt())) },
        fn("getenv") { a -> a.opt(1, "default") ?: PyNone },
    ).also {
        it.attrs["name"] = "moduforge"
        it.attrs["sep"] = "/"
        it.attrs["linesep"] = "\n"
    }

    // --- crypto ---------------------------------------------------------------------------------

    private fun requireBlocks(data: ByteArray) {
        if (data.size % BLOCK != 0) fail("ValueError", "data length must be a multiple of 16")
    }

    private fun requireKey(key: ByteArray) {
        if (key.size != 16 && key.size != 24 && key.size != 32) fail("ValueError", "AES key must be 16, 24 or 32 bytes")
    }

    private fun ecb(mode: Int, key: ByteArray): Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"))
    }

    private fun xor(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int): ByteArray =
        ByteArray(BLOCK) { (a[aOffset + it].toInt() xor b[bOffset + it].toInt()).toByte() }

    /** AES-IGE as MTProto uses it: [iv] is the previous ciphertext block followed by the previous plaintext block. */
    private fun ige(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        requireKey(key)
        requireBlocks(data)
        if (iv.size != 2 * BLOCK) fail("ValueError", "IGE iv must be 32 bytes")
        val cipher = ecb(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key)
        val out = ByteArray(data.size)
        var y = iv.copyOfRange(0, BLOCK) // previous ciphertext
        var x = iv.copyOfRange(BLOCK, 2 * BLOCK) // previous plaintext
        var at = 0
        while (at < data.size) {
            if (encrypt) {
                val plain = data.copyOfRange(at, at + BLOCK)
                val c = xor(cipher.doFinal(xor(plain, 0, y, 0)), 0, x, 0)
                c.copyInto(out, at)
                x = plain
                y = c
            } else {
                val c = data.copyOfRange(at, at + BLOCK)
                val p = xor(cipher.doFinal(xor(c, 0, x, 0)), 0, y, 0)
                p.copyInto(out, at)
                x = p
                y = c
            }
            at += BLOCK
        }
        return out
    }

    private fun cbc(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        requireKey(key)
        requireBlocks(data)
        if (iv.size != BLOCK) fail("ValueError", "CBC iv must be 16 bytes")
        return Cipher.getInstance("AES/CBC/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }
    }

    private fun crypto(): PyModule = module(
        "crypto",
        fn("aes_ige_encrypt") { a -> bytes(ige(bytesOf(a.req(0, "data")), bytesOf(a.req(1, "key")), bytesOf(a.req(2, "iv")), encrypt = true)) },
        fn("aes_ige_decrypt") { a -> bytes(ige(bytesOf(a.req(0, "data")), bytesOf(a.req(1, "key")), bytesOf(a.req(2, "iv")), encrypt = false)) },
        fn("aes_cbc_encrypt") { a -> bytes(cbc(bytesOf(a.req(0, "data")), bytesOf(a.req(1, "key")), bytesOf(a.req(2, "iv")), encrypt = true)) },
        fn("aes_cbc_decrypt") { a -> bytes(cbc(bytesOf(a.req(0, "data")), bytesOf(a.req(1, "key")), bytesOf(a.req(2, "iv")), encrypt = false)) },
        fn("aes_ecb_encrypt") { a ->
            val key = bytesOf(a.req(1, "key"))
            requireKey(key)
            val data = bytesOf(a.req(0, "data"))
            requireBlocks(data)
            bytes(ecb(Cipher.ENCRYPT_MODE, key).doFinal(data))
        },
        fn("aes_ecb_decrypt") { a ->
            val key = bytesOf(a.req(1, "key"))
            requireKey(key)
            val data = bytesOf(a.req(0, "data"))
            requireBlocks(data)
            bytes(ecb(Cipher.DECRYPT_MODE, key).doFinal(data))
        },
        // A CTR stream that keeps its state between calls: obfuscated transports encrypt one long stream.
        fn("aes_ctr") { a ->
            val key = bytesOf(a.req(0, "key"))
            val iv = bytesOf(a.req(1, "iv"))
            requireKey(key)
            if (iv.size != BLOCK) fail("ValueError", "CTR iv must be 16 bytes")
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            val o = PyObject("aes_ctr")
            o.attrs["update"] = PyBuiltin("update") { args, _ -> bytes(cipher.update(bytesOf(args[0])) ?: ByteArray(0)) }
            o
        },
        fn("aes_gcm_encrypt") { a ->
            val key = bytesOf(a.req(0, "key"))
            requireKey(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytesOf(a.req(1, "nonce"))))
                a.opt(3, "aad")?.takeIf { it !== PyNone }?.let { updateAAD(bytesOf(it)) }
            }
            bytes(cipher.doFinal(bytesOf(a.req(2, "data"))))
        },
        fn("aes_gcm_decrypt") { a ->
            val key = bytesOf(a.req(0, "key"))
            requireKey(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytesOf(a.req(1, "nonce"))))
                a.opt(3, "aad")?.takeIf { it !== PyNone }?.let { updateAAD(bytesOf(it)) }
            }
            try {
                bytes(cipher.doFinal(bytesOf(a.req(2, "data"))))
            } catch (e: java.security.GeneralSecurityException) {
                fail("ValueError", "authentication failed")
            }
        },
        fn("pbkdf2_hmac") { a -> pbkdf2(a) },
    )

    /** `hashlib.pbkdf2_hmac(hash_name, password, salt, iterations, dklen=None)`. */
    fun pbkdf2(a: Args): PyBytes {
        val digest = a.str(0, "hash_name")
        val password = bytesOf(a.req(1, "password"))
        val salt = bytesOf(a.req(2, "salt"))
        val iterations = a.long(3, "iterations").toInt()
        if (iterations < 1 || iterations > MAX_ITERATIONS) fail("ValueError", "iterations must be between 1 and $MAX_ITERATIONS")
        val m = mac(digest, password)
        val length = m.macLength
        val wanted = a.opt(4, "dklen")?.takeIf { it !== PyNone }?.let { Py.toInt(it) } ?: length
        if (wanted < 1 || wanted > MAX_DERIVED) fail("ValueError", "dklen out of range")
        val out = ByteArrayOutputStream()
        var block = 1
        while (out.size() < wanted) {
            m.update(salt)
            m.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = m.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = m.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            out.write(t)
            block++
        }
        return bytes(out.toByteArray().copyOf(wanted))
    }

    private const val BLOCK = 16
    private const val MAX_OUTPUT = 64 * 1024 * 1024
    private const val MAX_RANDOM = 1 shl 20
    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_DERIVED = 1 shl 16
}
