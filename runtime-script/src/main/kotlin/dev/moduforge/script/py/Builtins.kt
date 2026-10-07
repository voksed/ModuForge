package dev.moduforge.script.py

import java.math.BigDecimal
import java.math.BigInteger

/** A lazy iterator object such as the result of `map`, `zip` or `enumerate`. */
internal class PyIterator(private val inner: Iterator<Any?>) : Iterator<Any?> by inner

/** Positional and keyword arguments of a call, with the lookups a built-in needs. */
internal class Args(val pos: List<Any?>, val kw: Map<String, Any?>, val function: String) {
    /** Argument [i], or the keyword [name]; null when it was not given. */
    fun opt(i: Int, name: String): Any? = if (i < pos.size) pos[i] else kw[name]

    fun req(i: Int, name: String): Any? = opt(i, name) ?: throw Py.error("TypeError", "$function() missing required argument '$name' (pos ${i + 1})")

    fun str(i: Int, name: String, default: String? = null): String {
        val v = opt(i, name) ?: return default ?: throw Py.error("TypeError", "$function() missing required argument '$name'")
        return v as? String ?: throw Py.error("TypeError", "$function() argument '$name' must be str, not ${Py.typeName(v)}")
    }

    fun long(i: Int, name: String, default: Long? = null): Long {
        val v = opt(i, name) ?: return default ?: throw Py.error("TypeError", "$function() missing required argument '$name'")
        return if (v === PyNone && default != null) default else Py.toLong(v)
    }

    fun flag(name: String, default: Boolean = false): Boolean = kw[name]?.let { Py.truth(it) } ?: default
}

internal object Builtins {

    // --- classes of the runtime ---------------------------------------------------------------

    val OBJECT: PyClass = PyClass("object", emptyList(), ctor = { _, _ -> PyInstance(Builtins.OBJECT) })
    val TYPE: PyClass = PyClass("type", listOf(OBJECT))
    val INT: PyClass = PyClass("int", listOf(OBJECT), ctor = { a, k -> intOf(Args(a, k, "int")) })
    val BOOL: PyClass = PyClass("bool", listOf(INT), ctor = { a, _ -> a.firstOrNull()?.let(Py::truth) ?: false })
    val FLOAT: PyClass = PyClass("float", listOf(OBJECT), ctor = { a, k -> floatOf(Args(a, k, "float")) })
    val STR: PyClass = PyClass("str", listOf(OBJECT), ctor = { a, k -> strOf(Args(a, k, "str")) })
    val LIST: PyClass = PyClass("list", listOf(OBJECT), ctor = { a, _ -> PyList(if (a.isEmpty()) ArrayList() else Py.toList(a[0])) })
    val TUPLE: PyClass = PyClass("tuple", listOf(OBJECT), ctor = { a, _ -> if (a.isEmpty()) PyTuple(emptyList()) else if (a[0] is PyTuple) a[0] else PyTuple(Py.toList(a[0])) })
    val DICT: PyClass = PyClass("dict", listOf(OBJECT), ctor = { a, k -> dictOf(a, k) })
    val SET: PyClass = PyClass("set", listOf(OBJECT), ctor = { a, _ -> PySet(LinkedHashSet<Any?>().also { out -> if (a.isNotEmpty()) Py.iterate(a[0]).forEach { out += keyOf(it) } }) })
    val FROZENSET: PyClass = PyClass("frozenset", listOf(OBJECT), ctor = { a, _ -> PySet(LinkedHashSet<Any?>().also { out -> if (a.isNotEmpty()) Py.iterate(a[0]).forEach { out += keyOf(it) } }, frozen = true) })
    val BYTES: PyClass = PyClass("bytes", listOf(OBJECT), ctor = { a, k -> bytesOf(Args(a, k, "bytes")) })
    val NONETYPE: PyClass = PyClass("NoneType", listOf(OBJECT))
    val FUNCTION: PyClass = PyClass("function", listOf(OBJECT))
    val RANGE: PyClass = PyClass("range", listOf(OBJECT), ctor = { a, _ -> rangeOf(a) })
    val MODULE: PyClass = PyClass("module", listOf(OBJECT))
    val GENERATOR: PyClass = PyClass("generator", listOf(OBJECT))
    val ITERATOR: PyClass = PyClass("iterator", listOf(OBJECT))
    val SLICE: PyClass = PyClass("slice", listOf(OBJECT), ctor = { a, _ ->
        when (a.size) {
            1 -> PySlice(PyNone, a[0], PyNone)
            2 -> PySlice(a[0], a[1], PyNone)
            else -> PySlice(a.getOrElse(0) { PyNone }, a.getOrElse(1) { PyNone }, a.getOrElse(2) { PyNone })
        }
    })

    fun typeOf(v: Any?): PyClass = when (v) {
        null, PyNone -> NONETYPE
        is Boolean -> BOOL
        is Long, is Int, is BigInteger -> INT
        is Double -> FLOAT
        is String -> STR
        is PyList -> LIST
        is PyTuple -> TUPLE
        is PyDict -> DICT
        is PySet -> if (v.frozen) FROZENSET else SET
        is PyBytes -> BYTES
        is PyRange -> RANGE
        is PyFunction, is PyBuiltin, is BoundMethod -> FUNCTION
        is PyClass -> TYPE
        is PyModule -> MODULE
        is PyGenerator -> GENERATOR
        is PyIterator -> ITERATOR
        is PyInstance -> v.cls
        is PyObject -> PyClass(v.typeName, listOf(OBJECT))
        is PySlice -> SLICE
        else -> OBJECT
    }

    fun typeName(v: Any?): String = typeOf(v).name

    // --- exceptions ---------------------------------------------------------------------------

    private val exceptions = LinkedHashMap<String, PyClass>()

    fun exceptionClass(name: String): PyClass = exceptions.getOrPut(name) { exceptions.getValue("Exception") }

    private fun defineExceptions() {
        val base = PyClass("BaseException", listOf(OBJECT))
        base.attrs["__init__"] = PyBuiltin("__init__") { a, _ ->
            (a[0] as PyInstance).attrs["args"] = PyTuple(a.drop(1))
            PyNone
        }
        base.attrs["__str__"] = PyBuiltin("__str__") { a, _ ->
            val args = ((a[0] as PyInstance).attrs["args"] as? PyTuple)?.items.orEmpty()
            when (args.size) {
                0 -> ""
                1 -> Py.str(args[0])
                else -> Py.repr(PyTuple(args))
            }
        }
        exceptions["BaseException"] = base
        fun define(name: String, parent: String) {
            exceptions[name] = PyClass(name, listOf(exceptions.getValue(parent)))
        }
        for ((name, parent) in listOf(
            "Exception" to "BaseException", "KeyboardInterrupt" to "BaseException", "SystemExit" to "BaseException", "GeneratorExit" to "BaseException",
            "ArithmeticError" to "Exception", "ZeroDivisionError" to "ArithmeticError", "OverflowError" to "ArithmeticError", "FloatingPointError" to "ArithmeticError",
            "AssertionError" to "Exception", "AttributeError" to "Exception", "EOFError" to "Exception",
            "ImportError" to "Exception", "ModuleNotFoundError" to "ImportError",
            "LookupError" to "Exception", "IndexError" to "LookupError", "KeyError" to "LookupError",
            "NameError" to "Exception", "UnboundLocalError" to "NameError",
            "OSError" to "Exception", "ConnectionError" to "OSError", "ConnectionResetError" to "ConnectionError", "ConnectionRefusedError" to "ConnectionError",
            "ConnectionAbortedError" to "ConnectionError", "BrokenPipeError" to "ConnectionError", "TimeoutError" to "OSError", "FileNotFoundError" to "OSError", "PermissionError" to "OSError",
            "RuntimeError" to "Exception", "NotImplementedError" to "RuntimeError", "RecursionError" to "RuntimeError",
            "StopIteration" to "Exception", "SyntaxError" to "Exception", "TypeError" to "Exception",
            "ValueError" to "Exception", "UnicodeError" to "ValueError", "UnicodeDecodeError" to "UnicodeError", "UnicodeEncodeError" to "UnicodeError",
        )) define(name, parent)
        exceptions["IOError"] = exceptions.getValue("OSError")
        exceptions["EnvironmentError"] = exceptions.getValue("OSError")
        exceptions.getValue("KeyError").attrs["__str__"] = PyBuiltin("__str__") { a, _ ->
            val args = ((a[0] as PyInstance).attrs["args"] as? PyTuple)?.items.orEmpty()
            if (args.size == 1) Py.repr(args[0]) else Py.str(PyTuple(args))
        }
    }

    init {
        defineExceptions()
    }

    // --- global functions ---------------------------------------------------------------------

    fun globals(interp: Interpreter): MutableMap<String, Any?> {
        val g = LinkedHashMap<String, Any?>()
        fun def(name: String, fn: (Args) -> Any?) {
            g[name] = PyBuiltin(name) { a, k -> fn(Args(a, k, name)) }
        }
        for (cls in listOf(OBJECT, TYPE_CALLABLE, INT, FLOAT, STR, BOOL, LIST, TUPLE, DICT, SET, FROZENSET, BYTES, RANGE, SLICE)) g[cls.name] = cls
        for ((name, cls) in exceptions) g[name] = cls
        g["NotImplemented"] = NotImplementedValue
        g["Ellipsis"] = PyNone

        def("print") { a ->
            val sep = a.kw["sep"]?.let { if (it === PyNone) " " else Py.str(it) } ?: " "
            val end = a.kw["end"]?.let { if (it === PyNone) "\n" else Py.str(it) } ?: "\n"
            val text = a.pos.joinToString(sep) { Py.str(it) } + end
            interp.write(text)
            PyNone
        }
        def("len") { a -> Py.len(a.req(0, "obj")) }
        def("repr") { a -> Py.repr(a.req(0, "obj")) }
        def("ascii") { a -> Py.repr(a.req(0, "obj")) }
        def("abs") { a ->
            when (val v = a.req(0, "x")) {
                is Double -> Math.abs(v)
                is PyInstance -> interp.callSpecial(v, "__abs__", emptyList()).takeIf { it !== Missing } ?: throw Py.error("TypeError", "bad operand type for abs()")
                else -> if (Py.toBig(v).signum() < 0) Py.unary("-", v) else Py.toBig(v).let(Py::norm)
            }
        }
        def("min") { a -> extreme(a, smallest = true) }
        def("max") { a -> extreme(a, smallest = false) }
        def("sum") { a ->
            var total: Any? = a.opt(1, "start") ?: 0L
            Py.iterate(a.req(0, "iterable")).forEach { total = Py.binary("+", total, it) }
            total
        }
        def("sorted") { a ->
            PyList(sortList(Py.toList(a.req(0, "iterable")), a.kw["key"]?.takeIf { it !== PyNone }, a.flag("reverse")))
        }
        def("reversed") { a -> PyIterator(Py.toList(a.req(0, "seq")).asReversed().toList().iterator()) }
        def("enumerate") { a ->
            val iterator = Py.iterate(a.req(0, "iterable"))
            var n = a.long(1, "start", 0)
            PyIterator(object : Iterator<Any?> {
                override fun hasNext() = iterator.hasNext()
                override fun next(): Any? = PyTuple(listOf(n++, iterator.next()))
            })
        }
        def("zip") { a ->
            val iterators = a.pos.map { Py.iterate(it) }
            PyIterator(object : Iterator<Any?> {
                override fun hasNext() = iterators.isNotEmpty() && iterators.all { it.hasNext() }
                override fun next(): Any? = PyTuple(iterators.map { it.next() })
            })
        }
        def("map") { a ->
            val f = a.req(0, "function")
            val iterators = a.pos.drop(1).map { Py.iterate(it) }
            PyIterator(object : Iterator<Any?> {
                override fun hasNext() = iterators.isNotEmpty() && iterators.all { it.hasNext() }
                override fun next(): Any? = Py.call(f, iterators.map { it.next() })
            })
        }
        def("filter") { a ->
            val f = a.req(0, "function")
            val iterator = Py.iterate(a.req(1, "iterable"))
            PyIterator(object : Iterator<Any?> {
                var ready: Any? = Missing
                private fun fill() {
                    while (ready === Missing && iterator.hasNext()) {
                        val v = iterator.next()
                        if (if (f === PyNone) Py.truth(v) else Py.truth(Py.call(f, listOf(v)))) ready = v
                    }
                }
                override fun hasNext(): Boolean { fill(); return ready !== Missing }
                override fun next(): Any? { fill(); if (ready === Missing) throw NoSuchElementException(); return ready.also { ready = Missing } }
            })
        }
        def("any") { a -> Py.iterate(a.req(0, "iterable")).asSequence().any { Py.truth(it) } }
        def("all") { a -> Py.iterate(a.req(0, "iterable")).asSequence().all { Py.truth(it) } }
        def("iter") { a ->
            val v = a.req(0, "obj")
            if (v is PyGenerator || v is PyIterator) v else PyIterator(Py.iterate(v))
        }
        def("next") { a ->
            val v = a.req(0, "iterator")
            val default = a.pos.getOrNull(1)
            val iterator: Iterator<Any?> = when (v) {
                is PyGenerator, is PyIterator -> v as Iterator<Any?>
                else -> Py.iterate(v)
            }
            if (iterator.hasNext()) iterator.next() else default ?: throw Py.error("StopIteration", "")
        }
        def("isinstance") { a ->
            val v = a.req(0, "obj")
            val spec = a.req(1, "class")
            val classes = if (spec is PyTuple) spec.items else listOf(spec)
            val type = typeOf(v)
            classes.any { c -> c is PyClass && (type.isSubclassOf(c) || (c === FUNCTION && (v is PyFunction || v is PyBuiltin))) }
        }
        def("issubclass") { a ->
            val sub = a.req(0, "cls") as? PyClass ?: throw Py.error("TypeError", "issubclass() arg 1 must be a class")
            val spec = a.req(1, "class")
            (if (spec is PyTuple) spec.items else listOf(spec)).any { c -> c is PyClass && sub.isSubclassOf(c) }
        }
        def("callable") { a ->
            val v = a.req(0, "obj")
            v is PyFunction || v is PyBuiltin || v is BoundMethod || v is PyClass || (v is PyInstance && v.cls.lookup("__call__") !== Missing)
        }
        def("getattr") { a ->
            val found = Py.lookupAttr(a.req(0, "obj"), a.str(1, "name"))
            if (found !== Missing) found else a.pos.getOrNull(2) ?: throw Py.error("AttributeError", "'${Py.typeName(a.pos[0])}' object has no attribute '${a.pos[1]}'")
        }
        def("hasattr") { a -> Py.lookupAttr(a.req(0, "obj"), a.str(1, "name")) !== Missing }
        def("setattr") { a -> Py.setAttr(a.req(0, "obj"), a.str(1, "name"), a.req(2, "value")).let { PyNone } }
        def("delattr") { a -> ((a.pos[0] as? PyInstance)?.attrs?.remove(a.str(1, "name")) ?: throw Py.error("AttributeError", a.pos[1].toString())).let { PyNone } }
        def("divmod") { a -> PyTuple(listOf(Py.binary("//", a.req(0, "a"), a.req(1, "b")), Py.binary("%", a.req(0, "a"), a.req(1, "b")))) }
        def("pow") { a ->
            if (a.pos.size == 3) Py.norm(Py.toBig(a.pos[0]).modPow(Py.toBig(a.pos[1]), Py.toBig(a.pos[2]))) else Py.binary("**", a.req(0, "base"), a.req(1, "exp"))
        }
        def("round") { a -> round(a.req(0, "number"), a.opt(1, "ndigits")) }
        def("chr") { a -> String(Character.toChars(Py.toInt(a.req(0, "i")))) }
        def("ord") { a -> a.str(0, "c").let { if (it.codePointCount(0, it.length) != 1) throw Py.error("TypeError", "ord() expected a character") else it.codePointAt(0).toLong() } }
        def("hex") { a -> Py.toBig(a.req(0, "x")).let { (if (it.signum() < 0) "-0x" else "0x") + it.abs().toString(16) } }
        def("oct") { a -> Py.toBig(a.req(0, "x")).let { (if (it.signum() < 0) "-0o" else "0o") + it.abs().toString(8) } }
        def("bin") { a -> Py.toBig(a.req(0, "x")).let { (if (it.signum() < 0) "-0b" else "0b") + it.abs().toString(2) } }
        def("format") { a -> Formatting.format(a.req(0, "value"), a.str(1, "format_spec", "")) }
        def("hash") { a -> keyOf(a.req(0, "obj")).hashCode().toLong() }
        def("id") { a -> System.identityHashCode(a.req(0, "obj")).toLong() }
        def("input") { a ->
            interp.host.ask(a.str(0, "prompt", "")) ?: throw Py.error("EOFError", "EOF when reading a line")
        }
        def("open") { _ -> throw Py.error("OSError", "there is no file system in a module; use mf.storage") }
        def("exit") { a -> throw PyExit(a.pos.firstOrNull()) }
        g["quit"] = g["exit"]
        def("super") { a -> if (a.pos.size >= 2) PySuper(a.pos[0] as PyClass, a.pos[1]) else interp.zeroArgSuper() }
        def("property") { a -> PyProperty(a.req(0, "fget"), a.opt(1, "fset")) }
        def("staticmethod") { a -> PyStaticMethod(a.req(0, "f")) }
        def("classmethod") { a -> PyClassMethod(a.req(0, "f")) }
        def("vars") { a -> (a.req(0, "obj") as? PyInstance)?.let { PyDict(LinkedHashMap<Any?, Any?>(it.attrs)) } ?: throw Py.error("TypeError", "vars() argument must have __dict__") }
        def("globals") { _ -> PyDict(LinkedHashMap<Any?, Any?>(interp.mainScope.vars)) }
        def("dir") { a -> PyList((a.pos.firstOrNull() as? PyInstance)?.attrs?.keys?.sorted()?.toMutableList<Any?>() ?: ArrayList()) }
        return g
    }

    private val TYPE_CALLABLE: PyClass = PyClass("type", listOf(OBJECT), ctor = { a, _ ->
        if (a.size == 1) typeOf(a[0]) else throw Py.error("TypeError", "type() with three arguments is not supported")
    })

    private fun extreme(a: Args, smallest: Boolean): Any? {
        val items = if (a.pos.size == 1) Py.toList(a.pos[0]) else a.pos
        val key = a.kw["key"]?.takeIf { it !== PyNone }
        if (items.isEmpty()) return a.kw["default"] ?: throw Py.error("ValueError", "${if (smallest) "min" else "max"}() arg is an empty sequence")
        var best = items[0]
        var bestKey = if (key != null) Py.call(key, listOf(best)) else best
        for (i in 1 until items.size) {
            val candidate = items[i]
            val candidateKey = if (key != null) Py.call(key, listOf(candidate)) else candidate
            if (if (smallest) Py.compareOp("<", candidateKey, bestKey) else Py.compareOp(">", candidateKey, bestKey)) {
                best = candidate
                bestKey = candidateKey
            }
        }
        return best
    }

    fun sortList(items: MutableList<Any?>, key: Any?, reverse: Boolean): MutableList<Any?> {
        val keyed = items.map { (if (key != null) Py.call(key, listOf(it)) else it) to it }
        val comparator = Comparator<Pair<Any?, Any?>> { x, y ->
            try {
                Py.compare(x.first, y.first)
            } catch (e: Py.NaNOrder) {
                0
            }
        }
        // A stable sort with the opposite order keeps equal elements where they were, as `reverse=True` does in Python.
        val sorted = keyed.sortedWith(if (reverse) comparator.reversed() else comparator)
        return sorted.map { it.second }.toMutableList()
    }

    private fun round(v: Any?, digits: Any?): Any? {
        val n = if (digits == null || digits === PyNone) null else Py.toInt(digits)
        if (Py.isInt(v)) {
            if (n == null || n >= 0) return if (v is Boolean) Py.toLong(v) else v
            return Py.norm(BigDecimal(Py.toBig(v)).setScale(n, java.math.RoundingMode.HALF_EVEN).toBigInteger())
        }
        if (v is Double) {
            if (n == null) {
                if (v.isNaN() || v.isInfinite()) throw Py.error("ValueError", "cannot convert float ${Py.floatRepr(v)} to integer")
                return Py.norm(BigDecimal(Math.rint(v)).toBigInteger())
            }
            return Py.roundHalfEven(v, n)
        }
        if (v is PyInstance) {
            val r = Interpreter.current().callSpecial(v, "__round__", listOfNotNull(digits))
            if (r !== Missing) return r
        }
        throw Py.error("TypeError", "type ${Py.typeName(v)} doesn't define __round__ method")
    }

    // --- constructors -------------------------------------------------------------------------

    private fun intOf(a: Args): Any? {
        val v = a.opt(0, "x") ?: return 0L
        val base = a.opt(1, "base")
        return when {
            v is String -> Py.parseInt(v, if (base == null) 10 else Py.toInt(base))
            v is Double -> {
                if (v.isNaN()) throw Py.error("ValueError", "cannot convert float NaN to integer")
                if (v.isInfinite()) throw Py.error("OverflowError", "cannot convert float infinity to integer")
                Py.norm(BigDecimal(v).toBigInteger())
            }
            Py.isInt(v) -> if (v is Boolean) Py.toLong(v) else v
            v is PyInstance -> Interpreter.current().callSpecial(v, "__int__", emptyList()).takeIf { it !== Missing } ?: throw Py.error("TypeError", "int() argument must be a string or a number")
            v is PyBytes -> Py.parseInt(String(v.data, Charsets.ISO_8859_1))
            else -> throw Py.error("TypeError", "int() argument must be a string, a bytes-like object or a real number, not '${Py.typeName(v)}'")
        }
    }

    private fun floatOf(a: Args): Any? {
        val v = a.opt(0, "x") ?: return 0.0
        return when {
            v is String -> {
                val t = v.trim().lowercase().replace("_", "")
                when (t) {
                    "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
                    "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                    "nan", "+nan", "-nan" -> Double.NaN
                    else -> t.toDoubleOrNull()?.takeIf { !t.endsWith("f") && !t.endsWith("d") } ?: throw Py.error("ValueError", "could not convert string to float: ${Py.stringRepr(v)}")
                }
            }
            Py.isNumber(v) -> Py.toDouble(v)
            v is PyInstance -> Interpreter.current().callSpecial(v, "__float__", emptyList()).takeIf { it !== Missing } ?: throw Py.error("TypeError", "float() argument must be a string or a number")
            else -> throw Py.error("TypeError", "float() argument must be a string or a real number, not '${Py.typeName(v)}'")
        }
    }

    private fun strOf(a: Args): Any? {
        val v = a.opt(0, "object") ?: return ""
        if (v is PyBytes && a.opt(1, "encoding") != null) return String(v.data, Charsets.UTF_8)
        return Py.str(v)
    }

    private fun dictOf(args: List<Any?>, kwargs: Map<String, Any?>): PyDict {
        val dict = PyDict()
        if (args.isNotEmpty()) {
            val source = args[0]
            if (source is PyDict) dict.map.putAll(source.map) else {
                Py.iterate(source).forEach { item ->
                    val pair = Py.toList(item)
                    if (pair.size != 2) throw Py.error("ValueError", "dictionary update sequence element has length ${pair.size}; 2 is required")
                    dict.map[keyOf(pair[0])] = pair[1]
                }
            }
        }
        kwargs.forEach { (k, v) -> dict.map[k] = v }
        return dict
    }

    private fun bytesOf(a: Args): Any? {
        val v = a.opt(0, "source") ?: return PyBytes(ByteArray(0))
        return when {
            v is String -> PyBytes(v.toByteArray(Charsets.UTF_8))
            v is PyBytes -> v
            Py.isInt(v) -> PyBytes(ByteArray(Py.toInt(v)))
            else -> PyBytes(Py.toList(v).map { Py.toInt(it).toByte() }.toByteArray())
        }
    }

    private fun rangeOf(a: List<Any?>): PyRange {
        val n = a.map(Py::toLong)
        return when (n.size) {
            1 -> PyRange(0, n[0], 1)
            2 -> PyRange(n[0], n[1], 1)
            3 -> if (n[2] == 0L) throw Py.error("ValueError", "range() arg 3 must not be zero") else PyRange(n[0], n[1], n[2])
            else -> throw Py.error("TypeError", "range expected 1 to 3 arguments, got ${n.size}")
        }
    }

    // --- methods of the built-in types ---------------------------------------------------------

    /** The method [name] of a built-in value, bound to it; [Missing] when it has none. */
    fun method(obj: Any?, name: String): Any? {
        val table: Map<String, (Args) -> Any?>? = when (obj) {
            is String -> Methods.str(obj)
            is PyList -> Methods.list(obj)
            is PyDict -> Methods.dict(obj)
            is PySet -> Methods.set(obj)
            is PyTuple -> Methods.tuple(obj)
            is PyBytes -> Methods.bytes(obj)
            is Long, is Int, is BigInteger, is Boolean -> Methods.int(obj)
            is Double -> Methods.float(obj)
            is PyGenerator -> Methods.generator(obj)
            is PyIterator -> mapOf("__next__" to { a: Args -> if (obj.hasNext()) obj.next() else throw Py.error("StopIteration", "") })
            is PyRange -> mapOf(
                "index" to { a: Args -> (0 until obj.size).firstOrNull { obj.at(it) == Py.toLong(a.req(0, "value")) } ?: throw Py.error("ValueError", "value is not in range") },
                "count" to { a: Args -> if (Py.contains(obj, a.req(0, "value"))) 1L else 0L },
            )
            else -> null
        }
        if (obj is PyRange) {
            when (name) {
                "start" -> return obj.start
                "stop" -> return obj.stop
                "step" -> return obj.step
            }
        }
        if (obj is Double || obj is Long || obj is Boolean) when (name) {
            "real" -> return obj
            "imag" -> return if (obj is Double) 0.0 else 0L
        }
        val impl = table?.get(name) ?: return Missing
        return PyBuiltin(name) { a, k -> impl(Args(a, k, name)) }
    }

    /** `str.join` and friends used through the class: `map(str.lower, words)`. */
    fun classMethod(cls: PyClass, name: String): Any? {
        if (cls.ctor == null) return Missing
        if (cls === DICT && name == "fromkeys") {
            return PyBuiltin("fromkeys") { a, _ ->
                PyDict().also { d -> Py.iterate(a[0]).forEach { d.map[keyOf(it)] = a.getOrElse(1) { PyNone } } }
            }
        }
        if (cls === STR && name == "maketrans") return Missing
        return PyBuiltin(name) { a, k ->
            if (a.isEmpty()) throw Py.error("TypeError", "descriptor '$name' needs an argument")
            val bound = method(a[0], name)
            if (bound === Missing) throw Py.error("TypeError", "descriptor '$name' for '${cls.name}' objects doesn't apply to a '${Py.typeName(a[0])}' object")
            Py.call(bound, a.drop(1), k)
        }
    }
}
