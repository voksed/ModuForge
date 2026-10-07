package dev.moduforge.script.py

import java.math.BigInteger

/** The methods of the built-in types. Each function returns a table from method name to implementation. */
internal object Methods {

    private fun bad(message: String): Nothing = throw Py.error("TypeError", message)

    private fun chars(a: Args, i: Int): String? = a.opt(i, "chars")?.takeIf { it !== PyNone }?.let { it as? String ?: bad("strip arg must be None or str") }

    private fun trimWith(s: String, chars: String?, left: Boolean, right: Boolean): String {
        val matches: (Char) -> Boolean = if (chars == null) { c -> c.isWhitespace() } else { c -> c in chars }
        var start = 0
        var end = s.length
        if (left) while (start < end && matches(s[start])) start++
        if (right) while (end > start && matches(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun substringRange(a: Args, s: String, first: Int): Pair<Int, Int> {
        fun bound(v: Any?, default: Int): Int {
            if (v == null || v === PyNone) return default
            val n = Py.toInt(v)
            return (if (n < 0) n + s.length else n).coerceIn(0, s.length)
        }
        return bound(a.pos.getOrNull(first), 0) to bound(a.pos.getOrNull(first + 1), s.length)
    }

    private fun prefixes(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is PyTuple -> v.items.map { it as? String ?: bad("tuple for startswith must only contain str") }
        else -> bad("startswith first arg must be str or a tuple of str, not ${Py.typeName(v)}")
    }

    fun str(s: String): Map<String, (Args) -> Any?> = mapOf(
        "upper" to { _ -> s.uppercase() },
        "lower" to { _ -> s.lowercase() },
        "casefold" to { _ -> s.lowercase() },
        "capitalize" to { _ -> s.lowercase().replaceFirstChar { it.uppercase() } },
        "swapcase" to { _ -> s.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("") },
        "title" to { _ ->
            val sb = StringBuilder()
            var previousLetter = false
            for (c in s) {
                sb.append(if (c.isLetter()) (if (previousLetter) c.lowercaseChar() else c.uppercaseChar()) else c)
                previousLetter = c.isLetter()
            }
            sb.toString()
        },
        "strip" to { a -> trimWith(s, chars(a, 0), left = true, right = true) },
        "lstrip" to { a -> trimWith(s, chars(a, 0), left = true, right = false) },
        "rstrip" to { a -> trimWith(s, chars(a, 0), left = false, right = true) },
        "split" to { a -> split(s, a.opt(0, "sep"), a.opt(1, "maxsplit"), fromRight = false) },
        "rsplit" to { a -> split(s, a.opt(0, "sep"), a.opt(1, "maxsplit"), fromRight = true) },
        "splitlines" to { a ->
            val keep = a.flag("keepends") || (a.pos.getOrNull(0)?.let(Py::truth) ?: false)
            val out = ArrayList<Any?>()
            var start = 0
            var i = 0
            while (i < s.length) {
                if (s[i] == '\n' || s[i] == '\r') {
                    val end = if (s[i] == '\r' && i + 1 < s.length && s[i + 1] == '\n') i + 2 else i + 1
                    out += s.substring(start, if (keep) end else i)
                    start = end
                    i = end
                } else i++
            }
            if (start < s.length) out += s.substring(start)
            PyList(out)
        },
        "join" to { a ->
            Py.iterate(a.req(0, "iterable")).asSequence().joinToString(s) { it as? String ?: bad("sequence item: expected str instance, ${Py.typeName(it)} found") }
        },
        "startswith" to { a ->
            val (from, to) = substringRange(a, s, 1)
            prefixes(a.req(0, "prefix")).any { s.substring(from, to).startsWith(it) }
        },
        "endswith" to { a ->
            val (from, to) = substringRange(a, s, 1)
            prefixes(a.req(0, "suffix")).any { s.substring(from, to).endsWith(it) }
        },
        "find" to { a -> val (f, t) = substringRange(a, s, 1); s.substring(0, t).indexOf(a.str(0, "sub"), f).toLong() },
        "rfind" to { a -> val (f, t) = substringRange(a, s, 1); s.substring(0, t).lastIndexOf(a.str(0, "sub")).let { if (it < f) -1 else it }.toLong() },
        "index" to { a -> val (f, t) = substringRange(a, s, 1); s.substring(0, t).indexOf(a.str(0, "sub"), f).let { if (it < 0) throw Py.error("ValueError", "substring not found") else it.toLong() } },
        "rindex" to { a -> val (f, t) = substringRange(a, s, 1); s.substring(0, t).lastIndexOf(a.str(0, "sub")).let { if (it < f) throw Py.error("ValueError", "substring not found") else it.toLong() } },
        "count" to { a ->
            val (f, t) = substringRange(a, s, 1)
            val sub = a.str(0, "sub")
            val text = s.substring(f, t)
            if (sub.isEmpty()) (text.length + 1).toLong() else {
                var n = 0L
                var i = text.indexOf(sub)
                while (i >= 0) { n++; i = text.indexOf(sub, i + sub.length) }
                n
            }
        },
        "replace" to { a ->
            val old = a.str(0, "old")
            val new = a.str(1, "new")
            val count = a.long(2, "count", -1)
            if (count < 0) s.replace(old, new) else {
                val sb = StringBuilder()
                var from = 0
                var done = 0L
                while (done < count) {
                    val i = s.indexOf(old, from)
                    if (i < 0 || old.isEmpty()) break
                    sb.append(s, from, i).append(new)
                    from = i + old.length
                    done++
                }
                sb.append(s, from, s.length).toString()
            }
        },
        "format" to { a -> Formatting.strFormat(s, a.pos, a.kw) },
        "format_map" to { a ->
            val mapping = a.req(0, "mapping") as? PyDict ?: bad("format_map() argument must be a dict")
            Formatting.strFormat(s, emptyList(), mapping.map.entries.associate { it.key.toString() to it.value })
        },
        "isdigit" to { _ -> s.isNotEmpty() && s.all { it.isDigit() } },
        "isdecimal" to { _ -> s.isNotEmpty() && s.all { it.isDigit() } },
        "isnumeric" to { _ -> s.isNotEmpty() && s.all { it.isDigit() } },
        "isalpha" to { _ -> s.isNotEmpty() && s.all { it.isLetter() } },
        "isalnum" to { _ -> s.isNotEmpty() && s.all { it.isLetterOrDigit() } },
        "isspace" to { _ -> s.isNotEmpty() && s.all { it.isWhitespace() } },
        "isupper" to { _ -> s.any { it.isLetter() } && s.none { it.isLowerCase() } },
        "islower" to { _ -> s.any { it.isLetter() } && s.none { it.isUpperCase() } },
        "isidentifier" to { _ -> s.isNotEmpty() && (s[0].isLetter() || s[0] == '_') && s.all { it.isLetterOrDigit() || it == '_' } },
        "zfill" to { a ->
            val width = a.long(0, "width").toInt()
            val sign = if (s.startsWith("-") || s.startsWith("+")) s.substring(0, 1) else ""
            sign + s.substring(sign.length).padStart(width - sign.length, '0')
        },
        "ljust" to { a -> s.padEnd(a.long(0, "width").toInt(), a.str(1, "fillchar", " ").first()) },
        "rjust" to { a -> s.padStart(a.long(0, "width").toInt(), a.str(1, "fillchar", " ").first()) },
        "center" to { a ->
            val width = a.long(0, "width").toInt()
            val fill = a.str(1, "fillchar", " ").first()
            val total = (width - s.length).coerceAtLeast(0)
            val left = total / 2 + (total and width and 1)
            fill.toString().repeat(left) + s + fill.toString().repeat(total - left)
        },
        "partition" to { a ->
            val sep = a.str(0, "sep")
            val i = s.indexOf(sep)
            if (i < 0) PyTuple(listOf(s, "", "")) else PyTuple(listOf(s.substring(0, i), sep, s.substring(i + sep.length)))
        },
        "rpartition" to { a ->
            val sep = a.str(0, "sep")
            val i = s.lastIndexOf(sep)
            if (i < 0) PyTuple(listOf("", "", s)) else PyTuple(listOf(s.substring(0, i), sep, s.substring(i + sep.length)))
        },
        "removeprefix" to { a -> s.removePrefix(a.str(0, "prefix")) },
        "removesuffix" to { a -> s.removeSuffix(a.str(0, "suffix")) },
        "expandtabs" to { a -> s.replace("\t", " ".repeat(a.long(0, "tabsize", 8).toInt())) },
        "encode" to { a ->
            val charset = when (a.str(0, "encoding", "utf-8").lowercase().replace("_", "-")) {
                "utf-8", "utf8" -> Charsets.UTF_8
                "ascii" -> Charsets.US_ASCII
                "latin-1", "latin1", "iso-8859-1" -> Charsets.ISO_8859_1
                "utf-16" -> Charsets.UTF_16
                else -> throw Py.error("LookupError", "unknown encoding: ${a.pos.getOrNull(0)}")
            }
            PyBytes(s.toByteArray(charset))
        },
    )

    private fun split(s: String, sep: Any?, maxsplit: Any?, fromRight: Boolean): PyList {
        val limit = if (maxsplit == null || maxsplit === PyNone) -1 else Py.toInt(maxsplit)
        if (sep == null || sep === PyNone) {
            val words = ArrayList<String>()
            var i = 0
            val n = s.length
            if (!fromRight) {
                while (i < n) {
                    while (i < n && s[i].isWhitespace()) i++
                    if (i >= n) break
                    if (limit >= 0 && words.size == limit) {
                        words += s.substring(i).trimEnd()
                        break
                    }
                    val start = i
                    while (i < n && !s[i].isWhitespace()) i++
                    words += s.substring(start, i)
                }
            } else {
                var end = n
                while (end > 0) {
                    while (end > 0 && s[end - 1].isWhitespace()) end--
                    if (end <= 0) break
                    if (limit >= 0 && words.size == limit) {
                        words += s.substring(0, end).trimStart()
                        break
                    }
                    val stop = end
                    while (end > 0 && !s[end - 1].isWhitespace()) end--
                    words += s.substring(end, stop)
                }
                words.reverse()
            }
            return PyList(words.toMutableList<Any?>())
        }
        val separator = sep as? String ?: bad("must be str or None, not ${Py.typeName(sep)}")
        if (separator.isEmpty()) throw Py.error("ValueError", "empty separator")
        val parts = ArrayList<String>()
        if (!fromRight) {
            var from = 0
            while (limit < 0 || parts.size < limit) {
                val i = s.indexOf(separator, from)
                if (i < 0) break
                parts += s.substring(from, i)
                from = i + separator.length
            }
            parts += s.substring(from)
        } else {
            var end = s.length
            while (limit < 0 || parts.size < limit) {
                val i = s.lastIndexOf(separator, end - separator.length)
                if (i < 0 || end - separator.length < 0) break
                parts += s.substring(i + separator.length, end)
                end = i
            }
            parts += s.substring(0, end)
            parts.reverse()
        }
        return PyList(parts.toMutableList<Any?>())
    }

    fun list(l: PyList): Map<String, (Args) -> Any?> = mapOf(
        "append" to { a -> l.items.add(a.req(0, "object")); PyNone },
        "extend" to { a -> l.items.addAll(Py.toList(a.req(0, "iterable"))); PyNone },
        "insert" to { a ->
            val i = a.long(0, "index").let { if (it < 0) (it + l.items.size).coerceAtLeast(0) else it.coerceAtMost(l.items.size.toLong()) }.toInt()
            l.items.add(i, a.req(1, "object"))
            PyNone
        },
        "pop" to { a ->
            if (l.items.isEmpty()) throw Py.error("IndexError", "pop from empty list")
            val i = a.long(0, "index", -1).let { if (it < 0) it + l.items.size else it }
            if (i < 0 || i >= l.items.size) throw Py.error("IndexError", "pop index out of range")
            l.items.removeAt(i.toInt())
        },
        "remove" to { a ->
            val i = l.items.indexOfFirst { Py.eq(it, a.req(0, "value")) }
            if (i < 0) throw Py.error("ValueError", "list.remove(x): x not in list")
            l.items.removeAt(i)
            PyNone
        },
        "index" to { a ->
            val start = a.long(1, "start", 0).toInt().coerceAtLeast(0)
            val i = (start until l.items.size).firstOrNull { Py.eq(l.items[it], a.req(0, "value")) } ?: throw Py.error("ValueError", "${Py.repr(a.pos[0])} is not in list")
            i.toLong()
        },
        "count" to { a -> l.items.count { Py.eq(it, a.req(0, "value")) }.toLong() },
        "sort" to { a ->
            val sorted = Builtins.sortList(l.items, a.kw["key"]?.takeIf { it !== PyNone }, a.flag("reverse"))
            l.items.clear()
            l.items.addAll(sorted)
            PyNone
        },
        "reverse" to { _ -> l.items.reverse(); PyNone },
        "copy" to { _ -> PyList(ArrayList(l.items)) },
        "clear" to { _ -> l.items.clear(); PyNone },
    )

    fun tuple(t: PyTuple): Map<String, (Args) -> Any?> = mapOf(
        "count" to { a -> t.items.count { Py.eq(it, a.req(0, "value")) }.toLong() },
        "index" to { a -> t.items.indexOfFirst { Py.eq(it, a.req(0, "value")) }.let { if (it < 0) throw Py.error("ValueError", "tuple.index(x): x not in tuple") else it.toLong() } },
    )

    fun dict(d: PyDict): Map<String, (Args) -> Any?> = mapOf(
        "get" to { a -> d.map[keyOf(a.req(0, "key"))] ?: a.pos.getOrNull(1) ?: PyNone },
        "keys" to { _ -> PyList(ArrayList(d.map.keys)) },
        "values" to { _ -> PyList(ArrayList(d.map.values)) },
        "items" to { _ -> PyList(d.map.entries.map { PyTuple(listOf(it.key, it.value)) }.toMutableList<Any?>()) },
        "pop" to { a ->
            val key = keyOf(a.req(0, "key"))
            if (d.map.containsKey(key)) d.map.remove(key) else a.pos.getOrNull(1) ?: throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf(a.pos[0])))))
        },
        "popitem" to { _ ->
            val last = d.map.entries.lastOrNull() ?: throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf("popitem(): dictionary is empty")))))
            d.map.remove(last.key)
            PyTuple(listOf(last.key, last.value))
        },
        "setdefault" to { a ->
            val key = keyOf(a.req(0, "key"))
            if (d.map.containsKey(key)) d.map[key] else (a.pos.getOrNull(1) ?: PyNone).also { d.map[key] = it }
        },
        "update" to { a ->
            a.pos.firstOrNull()?.let { source ->
                if (source is PyDict) d.map.putAll(source.map) else Py.iterate(source).forEach { pair -> Py.toList(pair).let { d.map[keyOf(it[0])] = it[1] } }
            }
            a.kw.forEach { (k, v) -> d.map[k] = v }
            PyNone
        },
        "copy" to { _ -> PyDict(LinkedHashMap(d.map), d.factory) },
        "clear" to { _ -> d.map.clear(); PyNone },
    )

    fun set(s: PySet): Map<String, (Args) -> Any?> {
        fun others(a: Args) = a.pos.map { o -> LinkedHashSet<Any?>().also { out -> Py.iterate(o).forEach { out += keyOf(it) } } }
        fun mutable() { if (s.frozen) throw Py.error("AttributeError", "'frozenset' object has no attribute") }
        return mapOf(
            "add" to { a -> mutable(); s.items += keyOf(a.req(0, "elem")); PyNone },
            "remove" to { a ->
                mutable()
                if (!s.items.remove(keyOf(a.req(0, "elem")))) throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf(a.pos[0])))))
                PyNone
            },
            "discard" to { a -> mutable(); s.items.remove(keyOf(a.req(0, "elem"))); PyNone },
            "pop" to { _ ->
                mutable()
                val first = s.items.firstOrNull() ?: throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf("pop from an empty set")))))
                s.items.remove(first)
                first
            },
            "clear" to { _ -> mutable(); s.items.clear(); PyNone },
            "copy" to { _ -> PySet(LinkedHashSet(s.items), s.frozen) },
            "union" to { a -> PySet(LinkedHashSet(s.items).also { out -> others(a).forEach { out.addAll(it) } }, s.frozen) },
            "intersection" to { a -> PySet(LinkedHashSet(s.items).also { out -> others(a).forEach { out.retainAll(it) } }, s.frozen) },
            "difference" to { a -> PySet(LinkedHashSet(s.items).also { out -> others(a).forEach { out.removeAll(it) } }, s.frozen) },
            "symmetric_difference" to { a ->
                val other = others(a).first()
                PySet(LinkedHashSet<Any?>().also { out -> out.addAll(s.items.filter { it !in other }); out.addAll(other.filter { it !in s.items }) }, s.frozen)
            },
            "update" to { a -> mutable(); others(a).forEach { s.items.addAll(it) }; PyNone },
            "intersection_update" to { a -> mutable(); others(a).forEach { s.items.retainAll(it) }; PyNone },
            "difference_update" to { a -> mutable(); others(a).forEach { s.items.removeAll(it) }; PyNone },
            "issubset" to { a -> others(a).first().containsAll(s.items) },
            "issuperset" to { a -> s.items.containsAll(others(a).first()) },
            "isdisjoint" to { a -> others(a).first().none { it in s.items } },
        )
    }

    fun bytes(b: PyBytes): Map<String, (Args) -> Any?> = mapOf(
        "decode" to { a ->
            when (a.str(0, "encoding", "utf-8").lowercase().replace("_", "-")) {
                "latin-1", "latin1" -> String(b.data, Charsets.ISO_8859_1)
                "ascii" -> String(b.data, Charsets.US_ASCII)
                else -> String(b.data, Charsets.UTF_8)
            }
        },
        "hex" to { _ -> b.data.joinToString("") { "%02x".format(it) } },
        "startswith" to { a -> (a.req(0, "prefix") as? PyBytes)?.let { p -> b.data.size >= p.data.size && b.data.copyOf(p.data.size).contentEquals(p.data) } ?: false },
    )

    fun int(v: Any?): Map<String, (Args) -> Any?> = mapOf(
        "bit_length" to { _ -> Py.toBig(v).abs().bitLength().toLong() },
        "to_bytes" to { a ->
            val length = a.long(0, "length").toInt()
            val big = Py.toBig(v)
            var raw = big.toByteArray()
            if (raw.size > length && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
            if (raw.size > length) throw Py.error("OverflowError", "int too big to convert")
            val padded = ByteArray(length)
            val fill = if (big.signum() < 0) 0xFF.toByte() else 0
            padded.fill(fill)
            raw.copyInto(padded, length - raw.size)
            PyBytes(if (a.str(1, "byteorder", "big") == "little") padded.reversedArray() else padded)
        },
        "conjugate" to { _ -> v },
    )

    fun float(v: Double): Map<String, (Args) -> Any?> = mapOf(
        "is_integer" to { _ -> v % 1.0 == 0.0 && !v.isInfinite() },
        "conjugate" to { _ -> v },
    )

    fun generator(g: PyGenerator): Map<String, (Args) -> Any?> = mapOf(
        "__next__" to { _ -> g.pythonNext() },
        "send" to { a -> g.pythonNext(a.req(0, "value")) },
        "close" to { _ -> g.close(); PyNone },
        "throw" to { a ->
            val value = a.req(0, "type")
            val instance = when (value) {
                is PyClass -> Py.call(value, a.pos.drop(1)) as PyInstance
                else -> value as PyInstance
            }
            g.throwInto(PyError(instance))
        },
    )

    @Suppress("unused")
    private fun unusedBig(v: BigInteger) = v
}
