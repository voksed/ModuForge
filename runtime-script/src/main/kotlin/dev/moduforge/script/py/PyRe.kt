package dev.moduforge.script.py

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The `re` module on top of `java.util.regex`. */
internal object PyRe {

    private const val I = 2L
    private const val M = 8L
    private const val S = 16L
    private const val X = 64L

    /** A translated pattern: the Java form and the group names by index. */
    private class Compiled(val source: String, val flags: Long, val pattern: Pattern, val names: Map<String, Int>)

    private val cache = HashMap<Pair<String, Long>, Compiled>()

    private fun compile(source: String, flags: Long): Compiled = synchronized(cache) {
        cache.getOrPut(source to flags) {
            val (java, names) = translate(source)
            var f = 0
            if (flags and I != 0L) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if (flags and M != 0L) f = f or Pattern.MULTILINE
            if (flags and S != 0L) f = f or Pattern.DOTALL
            if (flags and X != 0L) f = f or Pattern.COMMENTS
            val pattern = try {
                Pattern.compile(java, f)
            } catch (e: PatternSyntaxException) {
                throw Py.error("ValueError", "bad regular expression: ${e.description}")
            }
            Compiled(source, flags, pattern, names)
        }
    }

    /** Turns `(?P<name>…)` and `(?P=name)` into plain groups and back-references by number. */
    private fun translate(source: String): Pair<String, Map<String, Int>> {
        val out = StringBuilder()
        val names = LinkedHashMap<String, Int>()
        var group = 0
        var i = 0
        var inClass = false
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length -> {
                    out.append(c).append(source[i + 1])
                    i += 2
                    continue
                }
                inClass -> {
                    if (c == ']') inClass = false
                    out.append(c)
                }
                c == '[' -> {
                    inClass = true
                    out.append(c)
                    if (source.startsWith("^]", i + 1)) { out.append("^]"); i += 2 } else if (source.startsWith("]", i + 1)) { out.append("]"); i += 1 }
                }
                c == '(' && source.startsWith("(?P<", i) -> {
                    val end = source.indexOf('>', i)
                    group++
                    names[source.substring(i + 4, end)] = group
                    out.append('(')
                    i = end + 1
                    continue
                }
                c == '(' && source.startsWith("(?P=", i) -> {
                    val end = source.indexOf(')', i)
                    val number = names[source.substring(i + 4, end)] ?: throw Py.error("ValueError", "unknown group name")
                    out.append("\\").append(number)
                    i = end + 1
                    continue
                }
                c == '(' && source.startsWith("(?", i) -> out.append(c)
                c == '(' -> {
                    group++
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to names
    }

    private fun groupIndex(c: Compiled, key: Any?): Int = when {
        key is String -> c.names[key] ?: throw Py.error("IndexError", "no such group")
        else -> Py.toInt(key).also { if (it < 0 || it > c.pattern.matcher("").groupCount()) throw Py.error("IndexError", "no such group") }
    }

    private fun matchObject(c: Compiled, m: Matcher, text: String): PyObject {
        val snapshot = m.toMatchResult()
        fun group(i: Int): Any? = snapshot.group(i) ?: PyNone
        val o = PyObject("re.Match")
        o.attrs["string"] = text
        o.attrs["re"] = patternObject(c)
        o.attrs["lastindex"] = (1..snapshot.groupCount()).lastOrNull { snapshot.group(it) != null }?.toLong() ?: PyNone
        o.attrs["group"] = PyBuiltin("group") { a, _ ->
            when (a.size) {
                0 -> group(0)
                1 -> group(groupIndex(c, a[0]))
                else -> PyTuple(a.map { group(groupIndex(c, it)) })
            }
        }
        o.attrs["__getitem__"] = PyBuiltin("__getitem__") { a, _ -> group(groupIndex(c, a[0])) }
        o.attrs["groups"] = PyBuiltin("groups") { a, _ ->
            PyTuple((1..snapshot.groupCount()).map { snapshot.group(it) ?: (a.firstOrNull() ?: PyNone) })
        }
        o.attrs["groupdict"] = PyBuiltin("groupdict") { _, _ ->
            PyDict(LinkedHashMap<Any?, Any?>().also { d -> c.names.forEach { (name, index) -> d[name] = group(index) } })
        }
        o.attrs["start"] = PyBuiltin("start") { a, _ -> snapshot.start(if (a.isEmpty()) 0 else groupIndex(c, a[0])).toLong() }
        o.attrs["end"] = PyBuiltin("end") { a, _ -> snapshot.end(if (a.isEmpty()) 0 else groupIndex(c, a[0])).toLong() }
        o.attrs["span"] = PyBuiltin("span") { a, _ ->
            val i = if (a.isEmpty()) 0 else groupIndex(c, a[0])
            PyTuple(listOf(snapshot.start(i).toLong(), snapshot.end(i).toLong()))
        }
        return o
    }

    private fun textOf(v: Any?): String = v as? String ?: throw Py.error("TypeError", "expected string or bytes-like object, got '${Py.typeName(v)}'")

    private fun expand(template: String, m: Matcher, c: Compiled): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch != '\\' || i + 1 >= template.length) {
                sb.append(ch)
                i++
                continue
            }
            val n = template[i + 1]
            when {
                n.isDigit() -> { sb.append(m.group(n - '0') ?: ""); i += 2 }
                n == 'g' && i + 2 < template.length && template[i + 2] == '<' -> {
                    val end = template.indexOf('>', i)
                    val key = template.substring(i + 3, end)
                    sb.append(m.group(key.toIntOrNull() ?: c.names[key] ?: throw Py.error("IndexError", "unknown group name '$key'")) ?: "")
                    i = end + 1
                }
                n == 'n' -> { sb.append('\n'); i += 2 }
                n == 't' -> { sb.append('\t'); i += 2 }
                n == '\\' -> { sb.append('\\'); i += 2 }
                else -> { sb.append(ch).append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    private fun patternObject(c: Compiled): PyObject {
        val o = PyObject("re.Pattern")
        o.attrs["pattern"] = c.source
        o.attrs["flags"] = c.flags
        o.attrs["groups"] = c.pattern.matcher("").groupCount().toLong()
        fun searchLike(name: String, how: (Matcher) -> Boolean) {
            o.attrs[name] = PyBuiltin(name) { a, _ ->
                val text = textOf(a[0])
                val m = c.pattern.matcher(text)
                if (a.size > 1) m.region(Py.toInt(a[1]).coerceIn(0, text.length), text.length)
                if (how(m)) matchObject(c, m, text) else PyNone
            }
        }
        searchLike("match") { it.lookingAt() }
        searchLike("search") { it.find() }
        searchLike("fullmatch") { it.matches() }
        o.attrs["findall"] = PyBuiltin("findall") { a, _ -> PyList(findAll(c, textOf(a[0]))) }
        o.attrs["finditer"] = PyBuiltin("finditer") { a, _ ->
            val text = textOf(a[0])
            val m = c.pattern.matcher(text)
            val found = ArrayList<Any?>()
            while (m.find()) found += matchObject(c, m, text)
            PyIterator(found.iterator())
        }
        o.attrs["sub"] = PyBuiltin("sub") { a, k -> sub(c, a[0], textOf(a[1]), a.getOrNull(2) ?: k["count"], false) }
        o.attrs["subn"] = PyBuiltin("subn") { a, k -> sub(c, a[0], textOf(a[1]), a.getOrNull(2) ?: k["count"], true) }
        o.attrs["split"] = PyBuiltin("split") { a, k -> split(c, textOf(a[0]), a.getOrNull(1) ?: k["maxsplit"]) }
        return o
    }

    private fun findAll(c: Compiled, text: String): MutableList<Any?> {
        val m = c.pattern.matcher(text)
        val out = ArrayList<Any?>()
        val groups = m.groupCount()
        while (m.find()) {
            out += when (groups) {
                0 -> m.group()
                1 -> m.group(1) ?: ""
                else -> PyTuple((1..groups).map { m.group(it) ?: "" })
            }
        }
        return out
    }

    private fun sub(c: Compiled, replacement: Any?, text: String, count: Any?, withCount: Boolean): Any? {
        val limit = if (count == null || count === PyNone) 0 else Py.toInt(count)
        val m = c.pattern.matcher(text)
        val sb = StringBuilder()
        var last = 0
        var n = 0
        while ((limit == 0 || n < limit) && m.find()) {
            sb.append(text, last, m.start())
            val replacementText = if (replacement is String) expand(replacement, m, c) else Py.str(Py.call(replacement, listOf(matchObject(c, m, text))))
            sb.append(replacementText)
            last = m.end()
            n++
            if (m.end() == m.start()) {
                if (m.end() < text.length) sb.append(text[m.end()])
                last = m.end() + 1
                if (last > text.length) break
                m.region(last, text.length)
            }
        }
        if (last < text.length) sb.append(text, last, text.length)
        return if (withCount) PyTuple(listOf(sb.toString(), n.toLong())) else sb.toString()
    }

    private fun split(c: Compiled, text: String, maxsplit: Any?): PyList {
        val limit = if (maxsplit == null || maxsplit === PyNone) 0 else Py.toInt(maxsplit)
        val m = c.pattern.matcher(text)
        val out = ArrayList<Any?>()
        var last = 0
        var n = 0
        while ((limit == 0 || n < limit) && m.find()) {
            if (m.end() == m.start() && m.start() == 0) continue
            out += text.substring(last, m.start())
            for (g in 1..m.groupCount()) out += m.group(g) ?: PyNone
            last = m.end()
            n++
        }
        out += text.substring(last)
        return PyList(out)
    }

    fun module(): PyModule {
        fun compiled(pattern: Any?, flags: Any?): Compiled =
            if (pattern is PyObject) compile(pattern.attrs["pattern"] as String, (pattern.attrs["flags"] as Long)) else compile(textOf(pattern), flags?.let(Py::toLong) ?: 0L)

        fun call(name: String, args: List<Any?>, kwargs: Map<String, Any?>, flagsIndex: Int): Any? {
            val pattern = args.getOrNull(0) ?: throw Py.error("TypeError", "re.$name() missing pattern")
            val flags = args.getOrNull(flagsIndex) ?: kwargs["flags"]
            val method = patternObject(compiled(pattern, flags)).attrs[name]
            return Py.call(method, args.drop(1).let { if (flagsIndex - 1 < it.size) it.take(flagsIndex - 1) else it }, kwargs - "flags")
        }
        val module = PyModule("re")
        val a = module.attrs
        a["I"] = I; a["IGNORECASE"] = I; a["M"] = M; a["MULTILINE"] = M; a["S"] = S; a["DOTALL"] = S; a["X"] = X; a["VERBOSE"] = X; a["A"] = 256L; a["U"] = 32L
        a["error"] = Builtins.exceptionClass("ValueError")
        a["compile"] = PyBuiltin("compile") { args, kw -> patternObject(compiled(args[0], args.getOrNull(1) ?: kw["flags"])) }
        a["escape"] = PyBuiltin("escape") { args, _ -> textOf(args[0]).replace(Regex("[^A-Za-z0-9_]")) { "\\" + it.value } }
        for (name in listOf("match", "search", "fullmatch", "findall", "finditer")) {
            a[name] = PyBuiltin(name) { args, kw -> call(name, args, kw, 3) }
        }
        // sub(pattern, repl, string, count=0, flags=0)
        for (name in listOf("sub", "subn")) {
            a[name] = PyBuiltin(name) { args, kw ->
                val method = patternObject(compiled(args[0], args.getOrNull(4) ?: kw["flags"])).attrs[name]
                Py.call(method, args.drop(1).take(3), kw - "flags")
            }
        }
        a["split"] = PyBuiltin("split") { args, kw ->
            Py.call(patternObject(compiled(args[0], args.getOrNull(3) ?: kw["flags"])).attrs["split"], args.drop(1).take(2), kw - "flags")
        }
        return module
    }
}
