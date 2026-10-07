package dev.moduforge.script.py

import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64

/** The part of the Python standard library the runtime offers. */
internal object Stdlib {

    /** The module [name], or null when the runtime has no such module. */
    fun create(name: String, interp: Interpreter): PyModule? {
        val native = when (name) {
            "json" -> json()
            "math" -> math()
            "time" -> time(interp)
            "random" -> random()
            "re" -> PyRe.module()
            "hashlib" -> hashlib()
            "base64" -> base64()
            "sys" -> sys(interp)
            "string" -> string()
            "urllib" -> PyModule("urllib")
            "urllib.parse" -> urlParse()
            "_native" -> PyModule("_native", linkedMapOf("defaultdict" to PyBuiltin("defaultdict") { a, _ -> PyDict(factory = a.firstOrNull()?.takeIf { it !== PyNone }) }))
            else -> null
        }
        if (native != null) return native
        // The rest is written in Python and ships with the runtime.
        return interp.host.bundled("lib/$name.py")?.let { interp.executeSource(name, it, "lib/$name.py") }
    }

    private fun module(name: String, vararg entries: Pair<String, Any?>) = PyModule(name, linkedMapOf(*entries))

    private fun fn(name: String, impl: (Args) -> Any?): Pair<String, Any?> = name to PyBuiltin(name) { a, k -> impl(Args(a, k, name)) }

    // --- json -----------------------------------------------------------------------------------

    private fun json(): PyModule {
        val decodeError = PyClass("JSONDecodeError", listOf(Builtins.exceptionClass("ValueError")))
        return module(
            "json",
            "JSONDecodeError" to decodeError,
            fn("dumps") { a ->
                val indent = a.kw["indent"]?.takeIf { it !== PyNone }?.let { if (it is String) it else " ".repeat(Py.toInt(it)) }
                val separators = a.kw["separators"] as? PyTuple
                val itemSep = separators?.items?.get(0) as? String ?: if (indent != null) "," else ", "
                val keySep = separators?.items?.get(1) as? String ?: ": "
                val sb = StringBuilder()
                writeJson(sb, a.req(0, "obj"), indent, itemSep, keySep, a.flag("sort_keys"), a.flag("ensure_ascii", true), 0)
                sb.toString()
            },
            fn("loads") { a ->
                val text = when (val v = a.req(0, "s")) {
                    is String -> v
                    is PyBytes -> String(v.data, Charsets.UTF_8)
                    else -> throw Py.error("TypeError", "the JSON object must be str, bytes or bytearray, not ${Py.typeName(v)}")
                }
                try {
                    JsonReader(text).readDocument()
                } catch (e: JsonSyntax) {
                    throw PyError(PyInstance(decodeError, linkedMapOf("args" to PyTuple(listOf("${e.message}: line 1 column ${e.position + 1} (char ${e.position})")))))
                }
            },
        )
    }

    private class JsonSyntax(message: String, val position: Int) : Exception(message)

    private class JsonReader(private val s: String) {
        private var i = 0

        fun readDocument(): Any? {
            skip()
            val v = value()
            skip()
            if (i < s.length) throw JsonSyntax("Extra data", i)
            return v
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            if (i >= s.length) throw JsonSyntax("Expecting value", i)
            return when (val c = s[i]) {
                '{' -> {
                    i++
                    val d = PyDict()
                    skip()
                    if (peek('}')) { i++; return d }
                    while (true) {
                        skip()
                        if (i >= s.length || s[i] != '"') throw JsonSyntax("Expecting property name enclosed in double quotes", i)
                        val key = string()
                        skip()
                        if (!peek(':')) throw JsonSyntax("Expecting ':' delimiter", i)
                        i++
                        skip()
                        d.map[key] = value()
                        skip()
                        if (peek(',')) { i++; continue }
                        if (peek('}')) { i++; return d }
                        throw JsonSyntax("Expecting ',' delimiter", i)
                    }
                    @Suppress("UNREACHABLE_CODE") d
                }
                '[' -> {
                    i++
                    val l = PyList()
                    skip()
                    if (peek(']')) { i++; return l }
                    while (true) {
                        skip()
                        l.items += value()
                        skip()
                        if (peek(',')) { i++; continue }
                        if (peek(']')) { i++; return l }
                        throw JsonSyntax("Expecting ',' delimiter", i)
                    }
                    @Suppress("UNREACHABLE_CODE") l
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", PyNone)
                'N' -> literal("NaN", Double.NaN)
                'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' || c.isDigit()) number() else throw JsonSyntax("Expecting value", i)
            }
        }

        private fun peek(c: Char) = i < s.length && s[i] == c

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonSyntax("Expecting value", i)
            i += word.length
            return value
        }

        private fun number(): Any? {
            val start = i
            if (peek('-')) i++
            if (s.startsWith("Infinity", i)) { i += 8; return Double.NEGATIVE_INFINITY }
            while (i < s.length && s[i].isDigit()) i++
            var isFloat = false
            if (peek('.')) { isFloat = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (isFloat) text.toDouble() else Py.norm(BigInteger(text))
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonSyntax("Unterminated string starting at", i)
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonSyntax("Unterminated string starting at", i)
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length)).toIntOrNull(16) ?: throw JsonSyntax("Invalid \\uXXXX escape", i)
                                sb.append(hex.toChar())
                                i += 4
                            }
                            else -> throw JsonSyntax("Invalid \\escape: $e", i - 1)
                        }
                    }
                    c < ' ' -> throw JsonSyntax("Invalid control character at", i - 1)
                    else -> sb.append(c)
                }
            }
        }
    }

    private fun writeJson(sb: StringBuilder, v: Any?, indent: String?, itemSep: String, keySep: String, sortKeys: Boolean, ascii: Boolean, level: Int) {
        fun newline(depth: Int) {
            if (indent != null) sb.append('\n').append(indent.repeat(depth))
        }
        when (v) {
            null, PyNone -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Long, is Int, is BigInteger -> sb.append(v.toString())
            is Double -> sb.append(if (v.isNaN()) "NaN" else if (v.isInfinite()) (if (v > 0) "Infinity" else "-Infinity") else Py.floatRepr(v))
            is String -> jsonString(sb, v, ascii)
            is PyList, is PyTuple -> {
                val items = if (v is PyList) v.items else (v as PyTuple).items
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                items.forEachIndexed { n, item ->
                    if (n > 0) sb.append(itemSep)
                    newline(level + 1)
                    writeJson(sb, item, indent, itemSep, keySep, sortKeys, ascii, level + 1)
                }
                newline(level)
                sb.append(']')
            }
            is PyDict -> {
                if (v.map.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                val entries = v.map.entries.map { (k, value) ->
                    (when (k) {
                        is String -> k
                        is Boolean -> if (k) "true" else "false"
                        null, PyNone -> "null"
                        is Long, is Int, is BigInteger, is Double -> Py.str(k)
                        else -> throw Py.error("TypeError", "keys must be str, int, float, bool or None, not ${Py.typeName(k)}")
                    }) to value
                }.let { if (sortKeys) it.sortedBy { e -> e.first } else it }
                entries.forEachIndexed { n, (k, value) ->
                    if (n > 0) sb.append(itemSep)
                    newline(level + 1)
                    jsonString(sb, k, ascii)
                    sb.append(keySep)
                    writeJson(sb, value, indent, itemSep, keySep, sortKeys, ascii, level + 1)
                }
                newline(level)
                sb.append('}')
            }
            else -> throw Py.error("TypeError", "Object of type ${Py.typeName(v)} is not JSON serializable")
        }
    }

    private fun jsonString(sb: StringBuilder, s: String, ascii: Boolean) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c < ' ' || (ascii && c.code > 126) -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    // --- math -----------------------------------------------------------------------------------

    private fun math(): PyModule {
        fun f1(name: String, op: (Double) -> Double) = fn(name) { a -> op(Py.toDouble(a.req(0, "x"))) }
        return module(
            "math",
            "pi" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI, "inf" to Double.POSITIVE_INFINITY, "nan" to Double.NaN,
            f1("sqrt") { if (it < 0) throw Py.error("ValueError", "math domain error") else Math.sqrt(it) },
            f1("fabs", Math::abs), f1("exp", Math::exp), f1("sin", Math::sin), f1("cos", Math::cos), f1("tan", Math::tan),
            f1("asin", Math::asin), f1("acos", Math::acos), f1("atan", Math::atan), f1("sinh", Math::sinh), f1("cosh", Math::cosh), f1("tanh", Math::tanh),
            f1("radians", Math::toRadians), f1("degrees", Math::toDegrees),
            f1("log2") { if (it <= 0) throw Py.error("ValueError", "math domain error") else Math.log(it) / Math.log(2.0) },
            f1("log10") { if (it <= 0) throw Py.error("ValueError", "math domain error") else Math.log10(it) },
            fn("log") { a ->
                val x = Py.toDouble(a.req(0, "x"))
                if (x <= 0) throw Py.error("ValueError", "math domain error")
                if (a.pos.size > 1) Math.log(x) / Math.log(Py.toDouble(a.pos[1])) else Math.log(x)
            },
            fn("atan2") { a -> Math.atan2(Py.toDouble(a.req(0, "y")), Py.toDouble(a.req(1, "x"))) },
            fn("hypot") { a -> Math.sqrt(a.pos.sumOf { Py.toDouble(it).let { d -> d * d } }) },
            fn("pow") { a -> Math.pow(Py.toDouble(a.req(0, "x")), Py.toDouble(a.req(1, "y"))) },
            fn("copysign") { a -> Math.copySign(Py.toDouble(a.req(0, "x")), Py.toDouble(a.req(1, "y"))) },
            fn("floor") { a -> a.req(0, "x").let { if (Py.isInt(it)) it else Py.norm(java.math.BigDecimal(Math.floor(Py.toDouble(it))).toBigInteger()) } },
            fn("ceil") { a -> a.req(0, "x").let { if (Py.isInt(it)) it else Py.norm(java.math.BigDecimal(Math.ceil(Py.toDouble(it))).toBigInteger()) } },
            fn("trunc") { a -> a.req(0, "x").let { if (Py.isInt(it)) it else Py.norm(java.math.BigDecimal(Py.toDouble(it)).toBigInteger()) } },
            fn("isnan") { a -> Py.toDouble(a.req(0, "x")).isNaN() },
            fn("isinf") { a -> Py.toDouble(a.req(0, "x")).isInfinite() },
            fn("isfinite") { a -> Py.toDouble(a.req(0, "x")).let { !it.isNaN() && !it.isInfinite() } },
            fn("isclose") { a ->
                val x = Py.toDouble(a.req(0, "a"))
                val y = Py.toDouble(a.req(1, "b"))
                val rel = a.kw["rel_tol"]?.let(Py::toDouble) ?: 1e-9
                val abs = a.kw["abs_tol"]?.let(Py::toDouble) ?: 0.0
                Math.abs(x - y) <= maxOf(rel * maxOf(Math.abs(x), Math.abs(y)), abs)
            },
            fn("gcd") { a -> a.pos.fold(BigInteger.ZERO) { acc, v -> acc.gcd(Py.toBig(v)) }.let(Py::norm) },
            fn("factorial") { a ->
                val n = Py.toLong(a.req(0, "n"))
                if (n < 0) throw Py.error("ValueError", "factorial() not defined for negative values")
                if (n > 5000) throw Py.error("OverflowError", "the result is too large")
                var r = BigInteger.ONE
                for (k in 2..n) r = r.multiply(BigInteger.valueOf(k))
                Py.norm(r)
            },
            fn("comb") { a ->
                val n = Py.toLong(a.req(0, "n"))
                val k = Py.toLong(a.req(1, "k"))
                if (k < 0 || n < 0) throw Py.error("ValueError", "n and k must be non-negative")
                var r = BigInteger.ONE
                for (j in 0 until minOf(k, n - k).coerceAtLeast(0)) r = r.multiply(BigInteger.valueOf(n - j)).divide(BigInteger.valueOf(j + 1))
                Py.norm(if (k > n) BigInteger.ZERO else r)
            },
            fn("fsum") { a -> Py.iterate(a.req(0, "iterable")).asSequence().sumOf { Py.toDouble(it) } },
            fn("prod") { a -> var t: Any? = 1L; Py.iterate(a.req(0, "iterable")).forEach { t = Py.binary("*", t, it) }; t },
        )
    }

    // --- time -----------------------------------------------------------------------------------

    private fun time(interp: Interpreter): PyModule {
        fun fields(t: Any?, utc: Boolean): ZonedDateTime {
            val seconds = if (t == null || t === PyNone) System.currentTimeMillis() / 1000.0 else Py.toDouble(t)
            return Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(if (utc) ZoneOffset.UTC else ZoneId.systemDefault())
        }
        fun struct(z: ZonedDateTime) = PyTuple(
            listOf(z.year.toLong(), z.monthValue.toLong(), z.dayOfMonth.toLong(), z.hour.toLong(), z.minute.toLong(), z.second.toLong(), (z.dayOfWeek.value - 1).toLong(), z.dayOfYear.toLong(), 0L),
        )
        fun fromStruct(t: Any?): ZonedDateTime {
            val v = Py.toList(t).map { Py.toInt(it) }
            return ZonedDateTime.of(v[0], v[1], v[2], v[3], v[4], v[5], 0, ZoneId.systemDefault())
        }
        return module(
            "time",
            fn("time") { _ -> System.currentTimeMillis() / 1000.0 },
            fn("time_ns") { _ -> System.currentTimeMillis() * 1_000_000L },
            fn("monotonic") { _ -> System.nanoTime() / 1e9 },
            fn("perf_counter") { _ -> System.nanoTime() / 1e9 },
            fn("sleep") { a ->
                try {
                    Thread.sleep((Py.toDouble(a.req(0, "secs")) * 1000).toLong().coerceAtLeast(0))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw PyInterrupted()
                }
                PyNone
            },
            fn("localtime") { a -> struct(fields(a.opt(0, "secs"), utc = false)) },
            fn("gmtime") { a -> struct(fields(a.opt(0, "secs"), utc = true)) },
            fn("mktime") { a -> fromStruct(a.req(0, "t")).toEpochSecond().toDouble() },
            fn("strftime") { a ->
                val z = if (a.pos.size > 1) fromStruct(a.pos[1]) else fields(null, utc = false)
                strftime(a.str(0, "format"), z)
            },
        )
    }

    /** The `strftime` directives scripts use, for the zoned moment [z]. */
    fun strftime(format: String, z: ZonedDateTime): String {
        val sb = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c != '%' || i + 1 >= format.length) {
                sb.append(c)
                i++
                continue
            }
            val d = format[i + 1]
            i += 2
            when (d) {
                'Y' -> sb.append(z.year)
                'y' -> sb.append("%02d".format(z.year % 100))
                'm' -> sb.append("%02d".format(z.monthValue))
                'd' -> sb.append("%02d".format(z.dayOfMonth))
                'H' -> sb.append("%02d".format(z.hour))
                'I' -> sb.append("%02d".format(if (z.hour % 12 == 0) 12 else z.hour % 12))
                'M' -> sb.append("%02d".format(z.minute))
                'S' -> sb.append("%02d".format(z.second))
                'f' -> sb.append("%06d".format(z.nano / 1000))
                'j' -> sb.append("%03d".format(z.dayOfYear))
                'p' -> sb.append(if (z.hour < 12) "AM" else "PM")
                'a' -> sb.append(z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                'A' -> sb.append(z.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH))
                'b' -> sb.append(z.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                'B' -> sb.append(z.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH))
                'Z' -> sb.append(z.zone.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                'z' -> sb.append(z.offset.id.replace(":", "").let { if (it == "Z") "+0000" else it })
                'w' -> sb.append(z.dayOfWeek.value % 7)
                'F' -> sb.append("%04d-%02d-%02d".format(z.year, z.monthValue, z.dayOfMonth))
                'T' -> sb.append("%02d:%02d:%02d".format(z.hour, z.minute, z.second))
                '%' -> sb.append('%')
                else -> sb.append('%').append(d)
            }
        }
        return sb.toString()
    }

    // --- random ---------------------------------------------------------------------------------

    private fun random(): PyModule {
        var rng = java.util.Random()
        return module(
            "random",
            fn("seed") { a -> rng = if (a.pos.isEmpty() || a.pos[0] === PyNone) java.util.Random() else java.util.Random(Py.str(a.pos[0]).hashCode().toLong() xor Py.toBig(if (Py.isInt(a.pos[0])) a.pos[0] else 0L).toLong()); PyNone },
            fn("random") { _ -> rng.nextDouble() },
            fn("uniform") { a -> Py.toDouble(a.req(0, "a")).let { lo -> lo + (Py.toDouble(a.req(1, "b")) - lo) * rng.nextDouble() } },
            fn("randint") { a ->
                val lo = Py.toLong(a.req(0, "a"))
                val hi = Py.toLong(a.req(1, "b"))
                if (hi < lo) throw Py.error("ValueError", "empty range for randint($lo, $hi)")
                lo + (rng.nextDouble() * (hi - lo + 1)).toLong().coerceAtMost(hi - lo)
            },
            fn("randrange") { a ->
                val r = if (a.pos.size == 1) PyRange(0, Py.toLong(a.pos[0]), 1) else PyRange(Py.toLong(a.pos[0]), Py.toLong(a.pos[1]), if (a.pos.size > 2) Py.toLong(a.pos[2]) else 1)
                if (r.size <= 0) throw Py.error("ValueError", "empty range for randrange()")
                r.at((rng.nextDouble() * r.size).toLong().coerceAtMost(r.size - 1))
            },
            fn("choice") { a ->
                val items = Py.toList(a.req(0, "seq"))
                if (items.isEmpty()) throw Py.error("IndexError", "Cannot choose from an empty sequence")
                items[rng.nextInt(items.size)]
            },
            fn("choices") { a ->
                val items = Py.toList(a.req(0, "population"))
                val k = a.long(1, "k", 1).toInt()
                PyList((0 until k).map { items[rng.nextInt(items.size)] }.toMutableList())
            },
            fn("shuffle") { a -> (a.req(0, "x") as? PyList ?: throw Py.error("TypeError", "shuffle() needs a list")).items.shuffle(rng); PyNone },
            fn("sample") { a ->
                val items = Py.toList(a.req(0, "population"))
                val k = a.long(1, "k").toInt()
                if (k > items.size || k < 0) throw Py.error("ValueError", "Sample larger than population or is negative")
                items.shuffle(rng)
                PyList(items.take(k).toMutableList())
            },
            fn("getrandbits") { a -> Py.norm(BigInteger(a.long(0, "k").toInt(), rng)) },
        )
    }

    // --- hashlib, base64 ------------------------------------------------------------------------

    private fun bytesOf(v: Any?): ByteArray = when (v) {
        is PyBytes -> v.data
        is String -> throw Py.error("TypeError", "Strings must be encoded before hashing")
        else -> throw Py.error("TypeError", "object supporting the buffer API required")
    }

    private fun hashlib(): PyModule {
        fun algorithm(name: String, java: String) = fn(name) { a ->
            val digest = MessageDigest.getInstance(java)
            a.pos.firstOrNull()?.let { digest.update(bytesOf(it)) }
            hashObject(digest, name)
        }
        return module(
            "hashlib",
            algorithm("md5", "MD5"), algorithm("sha1", "SHA-1"), algorithm("sha256", "SHA-256"), algorithm("sha512", "SHA-512"),
        )
    }

    private fun hashObject(digest: MessageDigest, name: String): PyObject {
        val o = PyObject("hash")
        o.attrs["name"] = name
        o.attrs["update"] = PyBuiltin("update") { a, _ -> digest.update(bytesOf(a[0])); PyNone }
        o.attrs["digest"] = PyBuiltin("digest") { _, _ -> PyBytes((digest.clone() as MessageDigest).digest()) }
        o.attrs["hexdigest"] = PyBuiltin("hexdigest") { _, _ -> (digest.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) } }
        o.attrs["copy"] = PyBuiltin("copy") { _, _ -> hashObject(digest.clone() as MessageDigest, name) }
        return o
    }

    private fun base64(): PyModule = module(
        "base64",
        fn("b64encode") { a -> PyBytes(Base64.getEncoder().encode(bytesOf(a.req(0, "s")))) },
        fn("b64decode") { a -> PyBytes(Base64.getMimeDecoder().decode(a.req(0, "s").let { if (it is String) it.toByteArray() else bytesOf(it) })) },
        fn("urlsafe_b64encode") { a -> PyBytes(Base64.getUrlEncoder().encode(bytesOf(a.req(0, "s")))) },
        fn("urlsafe_b64decode") { a -> PyBytes(Base64.getUrlDecoder().decode(a.req(0, "s").let { if (it is String) it.toByteArray() else bytesOf(it) }.let { String(it).trimEnd('=').toByteArray() })) },
        fn("b16encode") { a -> PyBytes(bytesOf(a.req(0, "s")).joinToString("") { "%02X".format(it) }.toByteArray()) },
    )

    // --- sys, string, urllib ----------------------------------------------------------------------

    private fun sys(interp: Interpreter): PyModule {
        fun stream(): PyObject {
            val o = PyObject("stream")
            o.attrs["write"] = PyBuiltin("write") { a, _ ->
                val text = Py.str(a[0])
                interp.write(text)
                text.length.toLong()
            }
            o.attrs["flush"] = PyBuiltin("flush") { _, _ -> interp.flushOutput(); PyNone }
            return o
        }
        return module(
            "sys",
            "argv" to PyList(arrayListOf<Any?>("main.py")), "path" to PyList(), "modules" to PyDict(),
            "version" to "3.11 (ModuForge)", "platform" to "android", "maxsize" to Long.MAX_VALUE,
            "stdout" to stream(), "stderr" to stream(),
            fn("exit") { a -> throw PyExit(a.pos.firstOrNull()) },
            fn("getrecursionlimit") { _ -> 450L },
        )
    }

    private fun string(): PyModule = module(
        "string",
        "ascii_lowercase" to "abcdefghijklmnopqrstuvwxyz", "ascii_uppercase" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "ascii_letters" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", "digits" to "0123456789",
        "hexdigits" to "0123456789abcdefABCDEF", "octdigits" to "01234567", "whitespace" to " \t\n\r\u000b\u000c",
        "punctuation" to "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
    )

    private fun urlParse(): PyModule = module(
        "urllib.parse",
        fn("quote") { a -> URLEncoder.encode(a.str(0, "string"), "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~").let { s -> a.str(1, "safe", "/").fold(s) { acc, c -> acc.replace(URLEncoder.encode(c.toString(), "UTF-8"), c.toString()) } } },
        fn("quote_plus") { a -> URLEncoder.encode(a.str(0, "string"), "UTF-8") },
        fn("unquote") { a -> URLDecoder.decode(a.str(0, "string").replace("+", "%2B"), "UTF-8") },
        fn("unquote_plus") { a -> URLDecoder.decode(a.str(0, "string"), "UTF-8") },
        fn("urlencode") { a ->
            val source = a.req(0, "query")
            val pairs = if (source is PyDict) source.map.entries.map { it.key to it.value } else Py.toList(source).map { Py.toList(it).let { p -> p[0] to p[1] } }
            pairs.joinToString("&") { (k, v) -> URLEncoder.encode(Py.str(k), "UTF-8") + "=" + URLEncoder.encode(Py.str(v), "UTF-8") }
        },
    )
}
