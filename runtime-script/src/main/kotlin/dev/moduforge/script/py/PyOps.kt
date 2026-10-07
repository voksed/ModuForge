package dev.moduforge.script.py

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Operations on Python values: conversion to text, truth, comparison, arithmetic, iteration, attributes. */
internal object Py {

    private fun interp(): Interpreter = Interpreter.current()

    fun error(type: String, message: String): PyError = PyError.of(type, message)

    // --- types ------------------------------------------------------------------------------

    fun typeName(v: Any?): String = Builtins.typeOf(v).name

    fun isInt(v: Any?) = v is Long || v is BigInteger || v is Boolean || v is Int

    fun isNumber(v: Any?) = isInt(v) || v is Double

    fun toBig(v: Any?): BigInteger = when (v) {
        is Long -> BigInteger.valueOf(v)
        is Int -> BigInteger.valueOf(v.toLong())
        is Boolean -> if (v) BigInteger.ONE else BigInteger.ZERO
        is BigInteger -> v
        else -> throw error("TypeError", "an integer is required, not '${typeName(v)}'")
    }

    fun norm(v: BigInteger): Any = if (v.bitLength() < 64) v.toLong() else v

    fun toLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is BigInteger -> throw error("OverflowError", "Python int too large to convert to a C long")
        else -> throw error("TypeError", "'${typeName(v)}' object cannot be interpreted as an integer")
    }

    fun toInt(v: Any?): Int = toLong(v).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    fun toDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is BigInteger -> v.toDouble()
        else -> throw error("TypeError", "must be real number, not ${typeName(v)}")
    }

    // --- text -------------------------------------------------------------------------------

    fun str(v: Any?): String = when (v) {
        is String -> v
        is PyInstance -> {
            val result = interp().callSpecial(v, "__str__", emptyList())
            if (result === Missing) repr(v) else (result as? String ?: throw error("TypeError", "__str__ returned non-string"))
        }
        else -> repr(v)
    }

    fun repr(v: Any?): String = when (v) {
        null, PyNone -> "None"
        is Boolean -> if (v) "True" else "False"
        is Long, is Int, is BigInteger -> v.toString()
        is Double -> floatRepr(v)
        is String -> stringRepr(v)
        is PyBytes -> "b" + stringRepr(String(v.data, Charsets.ISO_8859_1))
        is PyList -> v.items.joinToString(", ", "[", "]") { if (it === v) "[...]" else repr(it) }
        is PyTuple -> if (v.items.size == 1) "(${repr(v.items[0])},)" else v.items.joinToString(", ", "(", ")") { repr(it) }
        is PyDict -> v.map.entries.joinToString(", ", "{", "}") { repr(it.key) + ": " + repr(it.value) }
        is PySet -> if (v.items.isEmpty()) (if (v.frozen) "frozenset()" else "set()") else v.items.joinToString(", ", if (v.frozen) "frozenset({" else "{", if (v.frozen) "})" else "}") { repr(it) }
        is PyRange -> if (v.step == 1L) "range(${v.start}, ${v.stop})" else "range(${v.start}, ${v.stop}, ${v.step})"
        is PySlice -> "slice(${repr(v.lo)}, ${repr(v.hi)}, ${repr(v.step)})"
        is PyFunction -> "<function ${v.name}>"
        is PyBuiltin -> "<built-in function ${v.name}>"
        is BoundMethod -> "<bound method>"
        is PyClass -> "<class '${v.name}'>"
        is PyModule -> "<module '${v.name}'>"
        is PyObject -> "<${v.typeName} object>"
        is PyGenerator -> "<generator object>"
        is PyInstance -> {
            val result = interp().callSpecial(v, "__repr__", emptyList())
            if (result === Missing) {
                if (v.cls.isSubclassOf(Builtins.exceptionClass("BaseException"))) {
                    val args = (v.attrs["args"] as? PyTuple)?.items.orEmpty()
                    "${v.cls.name}(${args.joinToString(", ") { repr(it) }})"
                } else {
                    "<${v.cls.name} object>"
                }
            } else {
                result as? String ?: throw error("TypeError", "__repr__ returned non-string")
            }
        }
        else -> v.toString()
    }

    fun stringRepr(s: String): String {
        val quote = if ('\'' in s && '"' !in s) '"' else '\''
        val sb = StringBuilder().append(quote)
        for (c in s) {
            when {
                c == quote || c == '\\' -> sb.append('\\').append(c)
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\u007f' -> sb.append("\\x%02x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append(quote).toString()
    }

    /** `repr` of a float the way Python writes it: `1.0`, `0.1`, `1e+16`, `inf`. */
    fun floatRepr(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val decimal = BigDecimal(java.lang.Double.toString(Math.abs(d)))
        val digits = decimal.unscaledValue().toString().trimEnd('0').ifEmpty { "0" }
        // Decimal exponent of the first digit.
        val exponent = decimal.precision() - decimal.scale() - 1
        val sign = if (d < 0) "-" else ""
        return sign + if (exponent < -4 || exponent >= 16) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            mantissa + "e" + (if (exponent < 0) "-" else "+") + Math.abs(exponent).toString().padStart(2, '0')
        } else if (exponent < 0) {
            "0." + "0".repeat(-exponent - 1) + digits
        } else if (digits.length <= exponent + 1) {
            digits + "0".repeat(exponent + 1 - digits.length) + ".0"
        } else {
            digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1)
        }
    }

    // --- truth, equality, order ---------------------------------------------------------------

    fun truth(v: Any?): Boolean = when (v) {
        null, PyNone -> false
        is Boolean -> v
        is Long -> v != 0L
        is Int -> v != 0
        is Double -> v != 0.0
        is BigInteger -> v.signum() != 0
        is String -> v.isNotEmpty()
        is PyList -> v.items.isNotEmpty()
        is PyTuple -> v.items.isNotEmpty()
        is PyDict -> v.map.isNotEmpty()
        is PySet -> v.items.isNotEmpty()
        is PyBytes -> v.data.isNotEmpty()
        is PyRange -> v.size > 0
        is PyInstance -> {
            val b = interp().callSpecial(v, "__bool__", emptyList())
            if (b !== Missing) truth(b) else {
                val n = interp().callSpecial(v, "__len__", emptyList())
                if (n !== Missing) toLong(n) != 0L else true
            }
        }
        else -> true
    }

    fun eq(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (isNumber(a) && isNumber(b)) {
            return if (a is Double || b is Double) toDouble(a) == toDouble(b) else toBig(a) == toBig(b)
        }
        return when {
            a is String && b is String -> a == b
            (a == null || a === PyNone) -> b == null || b === PyNone
            a is PyList && b is PyList -> a.items.size == b.items.size && a.items.indices.all { eq(a.items[it], b.items[it]) }
            a is PyTuple && b is PyTuple -> a.items.size == b.items.size && a.items.indices.all { eq(a.items[it], b.items[it]) }
            a is PyDict && b is PyDict -> a.map.size == b.map.size && a.map.all { (k, v) -> b.map.containsKey(k) && eq(v, b.map[k]) }
            a is PySet && b is PySet -> a.items == b.items
            a is PyBytes && b is PyBytes -> a == b
            a is PyRange && b is PyRange -> a == b
            a is PyInstance -> {
                val r = interp().callSpecial(a, "__eq__", listOf(b))
                if (r !== Missing && r !== NotImplementedValue) truth(r) else if (b is PyInstance) {
                    val reverse = interp().callSpecial(b, "__eq__", listOf(a))
                    reverse !== Missing && reverse !== NotImplementedValue && truth(reverse)
                } else false
            }
            b is PyInstance -> {
                val r = interp().callSpecial(b, "__eq__", listOf(a))
                r !== Missing && r !== NotImplementedValue && truth(r)
            }
            else -> a == b
        }
    }

    /** Negative, zero or positive; raises `TypeError` for values that have no order. */
    fun compare(a: Any?, b: Any?, op: String = "<"): Int {
        if (isNumber(a) && isNumber(b)) {
            return if (a is Double || b is Double) {
                val x = toDouble(a)
                val y = toDouble(b)
                if (x.isNaN() || y.isNaN()) throw NaNOrder else x.compareTo(y).let { if (x == y) 0 else it }
            } else toBig(a).compareTo(toBig(b))
        }
        if (a is String && b is String) return a.compareTo(b)
        if (a is PyList && b is PyList) return compareSequences(a.items, b.items)
        if (a is PyTuple && b is PyTuple) return compareSequences(a.items, b.items)
        if (a is PyBytes && b is PyBytes) return a.data.toString(Charsets.ISO_8859_1).compareTo(b.data.toString(Charsets.ISO_8859_1))
        if (a is PySet && b is PySet) {
            return when {
                a.items == b.items -> 0
                b.items.containsAll(a.items) -> -1
                a.items.containsAll(b.items) -> 1
                else -> throw NaNOrder
            }
        }
        if (a is PyInstance) {
            val name = when (op) { "<" -> "__lt__"; "<=" -> "__le__"; ">" -> "__gt__"; else -> "__ge__" }
            val r = interp().callSpecial(a, name, listOf(b))
            if (r !== Missing && r !== NotImplementedValue) return if (truth(r)) orderFor(op, true) else orderFor(op, false)
        }
        if (b is PyInstance) {
            val mirrored = when (op) { "<" -> "__gt__"; "<=" -> "__ge__"; ">" -> "__lt__"; else -> "__le__" }
            val r = interp().callSpecial(b, mirrored, listOf(a))
            if (r !== Missing && r !== NotImplementedValue) return if (truth(r)) orderFor(op, true) else orderFor(op, false)
        }
        throw error("TypeError", "'$op' not supported between instances of '${typeName(a)}' and '${typeName(b)}'")
    }

    /** A comparison involving NaN: every ordering is false. */
    object NaNOrder : RuntimeException(null, null, false, false)

    private fun orderFor(op: String, holds: Boolean): Int = when (op) {
        "<" -> if (holds) -1 else 1
        "<=" -> if (holds) -1 else 1
        ">" -> if (holds) 1 else -1
        else -> if (holds) 1 else -1
    }

    private fun compareSequences(a: List<Any?>, b: List<Any?>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            if (!eq(a[i], b[i])) return compare(a[i], b[i])
        }
        return a.size.compareTo(b.size)
    }

    fun compareOp(op: String, a: Any?, b: Any?): Boolean = when (op) {
        "==" -> eq(a, b)
        "!=", "<>" -> !eq(a, b)
        "is" -> identical(a, b)
        "is not" -> !identical(a, b)
        "in" -> contains(b, a)
        "not in" -> !contains(b, a)
        else -> try {
            val c = compare(a, b, op)
            when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 }
        } catch (e: NaNOrder) {
            false
        }
    }

    fun identical(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        (a == null || a === PyNone) && (b == null || b === PyNone) -> true
        a is Boolean && b is Boolean -> a == b
        a is Long && b is Long -> a == b
        a is String && b is String -> a == b
        else -> false
    }

    // --- arithmetic ---------------------------------------------------------------------------

    private val SPECIAL = mapOf(
        "+" to "__add__", "-" to "__sub__", "*" to "__mul__", "/" to "__truediv__", "//" to "__floordiv__", "%" to "__mod__",
        "**" to "__pow__", "@" to "__matmul__", "&" to "__and__", "|" to "__or__", "^" to "__xor__", "<<" to "__lshift__", ">>" to "__rshift__",
    )
    private val REFLECTED = SPECIAL.mapValues { "__r" + it.value.removePrefix("__") }

    fun binary(op: String, a: Any?, b: Any?): Any? {
        if (a is PyInstance) {
            val r = interp().callSpecial(a, SPECIAL.getValue(op), listOf(b))
            if (r !== Missing && r !== NotImplementedValue) return r
        }
        if (b is PyInstance) {
            val r = interp().callSpecial(b, REFLECTED.getValue(op), listOf(a))
            if (r !== Missing && r !== NotImplementedValue) return r
        }
        if (isNumber(a) && isNumber(b)) return arithmetic(op, a, b)
        when (op) {
            "+" -> when {
                a is String && b is String -> return a + b
                a is PyList && b is PyList -> return PyList((a.items + b.items).toMutableList())
                a is PyTuple && b is PyTuple -> return PyTuple(a.items + b.items)
                a is PyBytes && b is PyBytes -> return PyBytes(a.data + b.data)
            }
            "*" -> {
                if (isInt(b)) repeat(a, toLong(b))?.let { return it }
                if (isInt(a)) repeat(b, toLong(a))?.let { return it }
            }
            "%" -> if (a is String) return Formatting.percent(a, b)
            "|", "&", "-", "^" -> if (a is PySet && b is PySet) return setOperation(op, a, b)
            "|" -> if (a is PyDict && b is PyDict) return PyDict(LinkedHashMap(a.map).also { it.putAll(b.map) })
        }
        throw error("TypeError", "unsupported operand type(s) for $op: '${typeName(a)}' and '${typeName(b)}'")
    }

    private fun repeat(v: Any?, n: Long): Any? {
        val times = n.coerceAtLeast(0).toInt()
        return when (v) {
            is String -> v.repeat(times)
            is PyList -> PyList(ArrayList<Any?>().also { out -> repeat(times) { out.addAll(v.items) } })
            is PyTuple -> PyTuple(ArrayList<Any?>().also { out -> repeat(times) { out.addAll(v.items) } })
            else -> null
        }
    }

    private fun setOperation(op: String, a: PySet, b: PySet): PySet {
        val out = LinkedHashSet(a.items)
        when (op) {
            "|" -> out.addAll(b.items)
            "&" -> out.retainAll(b.items)
            "-" -> out.removeAll(b.items)
            else -> {
                out.addAll(b.items)
                out.removeAll(a.items.intersect(b.items))
            }
        }
        return PySet(out, a.frozen)
    }

    private fun arithmetic(op: String, a: Any?, b: Any?): Any? {
        if (a is Double || b is Double) {
            val x = toDouble(a)
            val y = toDouble(b)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0.0) throw error("ZeroDivisionError", "float division by zero") else x / y
                "//" -> if (y == 0.0) throw error("ZeroDivisionError", "float floor division by zero") else Math.floor(x / y)
                "%" -> if (y == 0.0) throw error("ZeroDivisionError", "float modulo") else x - Math.floor(x / y) * y
                "**" -> Math.pow(x, y)
                else -> throw error("TypeError", "unsupported operand type(s) for $op: 'float' and '${typeName(if (b is Double) a else b)}'")
            }
        }
        if (a is Long && b is Long) {
            when (op) {
                "+" -> { val r = a + b; if (((a xor r) and (b xor r)) >= 0) return r }
                "-" -> { val r = a - b; if (((a xor b) and (a xor r)) >= 0) return r }
                "*" -> { val hi = Math.multiplyHigh(a, b); val lo = a * b; if ((hi == 0L && lo >= 0) || (hi == -1L && lo < 0)) return lo }
            }
        }
        val x = toBig(a)
        val y = toBig(b)
        return when (op) {
            "+" -> norm(x + y)
            "-" -> norm(x - y)
            "*" -> norm(x * y)
            "/" -> {
                if (y.signum() == 0) throw error("ZeroDivisionError", "division by zero")
                BigDecimal(x).divide(BigDecimal(y), java.math.MathContext.DECIMAL64).toDouble().let { if (x.bitLength() < 53 && y.bitLength() < 53) x.toDouble() / y.toDouble() else it }
            }
            "//" -> {
                if (y.signum() == 0) throw error("ZeroDivisionError", "integer division or modulo by zero")
                val qr = x.divideAndRemainder(y)
                norm(if (qr[1].signum() != 0 && (qr[1].signum() != y.signum())) qr[0] - BigInteger.ONE else qr[0])
            }
            "%" -> {
                if (y.signum() == 0) throw error("ZeroDivisionError", "integer division or modulo by zero")
                val r = x.mod(y.abs())
                norm(if (y.signum() < 0 && r.signum() != 0) r + y else r)
            }
            "**" -> when {
                y.signum() < 0 -> Math.pow(x.toDouble(), y.toDouble())
                y.bitLength() > 31 || (x.bitLength().toLong() * y.toInt() > 4_000_000) -> throw error("OverflowError", "the result is too large")
                else -> norm(x.pow(y.toInt()))
            }
            "&" -> norm(x.and(y))
            "|" -> norm(x.or(y))
            "^" -> norm(x.xor(y))
            "<<" -> if (y.signum() < 0) throw error("ValueError", "negative shift count") else if (y.toInt() > 100_000) throw error("OverflowError", "the result is too large") else norm(x.shiftLeft(y.toInt()))
            ">>" -> if (y.signum() < 0) throw error("ValueError", "negative shift count") else norm(x.shiftRight(minOf(y.toInt(), 1_000_000)))
            else -> throw error("TypeError", "unsupported operand type(s) for $op: 'int' and 'int'")
        }
    }

    fun unary(op: String, v: Any?): Any? {
        if (v is PyInstance) {
            val name = when (op) { "-" -> "__neg__"; "+" -> "__pos__"; else -> "__invert__" }
            val r = interp().callSpecial(v, name, emptyList())
            if (r !== Missing) return r
        }
        return when (op) {
            "-" -> if (v is Double) -v else if (isInt(v)) arithmetic("-", 0L, v) else throw error("TypeError", "bad operand type for unary -: '${typeName(v)}'")
            "+" -> if (isNumber(v)) (if (v is Boolean) toLong(v) else v) else throw error("TypeError", "bad operand type for unary +: '${typeName(v)}'")
            else -> if (isInt(v)) norm(toBig(v).not()) else throw error("TypeError", "bad operand type for unary ~: '${typeName(v)}'")
        }
    }

    // --- containers -------------------------------------------------------------------------

    fun len(v: Any?): Long = when (v) {
        is String -> v.codePointCount(0, v.length).toLong()
        is PyList -> v.items.size.toLong()
        is PyTuple -> v.items.size.toLong()
        is PyDict -> v.map.size.toLong()
        is PySet -> v.items.size.toLong()
        is PyBytes -> v.data.size.toLong()
        is PyRange -> v.size
        is PyInstance -> {
            val r = interp().callSpecial(v, "__len__", emptyList())
            if (r === Missing) throw error("TypeError", "object of type '${typeName(v)}' has no len()") else toLong(r)
        }
        else -> throw error("TypeError", "object of type '${typeName(v)}' has no len()")
    }

    fun contains(container: Any?, item: Any?): Boolean = when (container) {
        is String -> if (item is String) container.contains(item) else throw error("TypeError", "'in <string>' requires string as left operand, not ${typeName(item)}")
        is PyList -> container.items.any { eq(it, item) }
        is PyTuple -> container.items.any { eq(it, item) }
        is PyDict -> container.map.containsKey(keyOf(item))
        is PySet -> container.items.contains(keyOf(item))
        is PyRange -> isInt(item) && toLong(item).let { n -> if (container.step > 0) n >= container.start && n < container.stop && (n - container.start) % container.step == 0L else n <= container.start && n > container.stop && (container.start - n) % -container.step == 0L }
        is PyBytes -> if (isInt(item)) container.data.any { (it.toInt() and 0xFF).toLong() == toLong(item) } else false
        is PyInstance -> {
            val r = interp().callSpecial(container, "__contains__", listOf(item))
            if (r !== Missing) truth(r) else iterate(container).asSequence().any { eq(it, item) }
        }
        else -> iterate(container).asSequence().any { eq(it, item) }
    }

    fun iterate(v: Any?): Iterator<Any?> = when (v) {
        is PyList -> object : Iterator<Any?> {
            var i = 0
            override fun hasNext() = i < v.items.size
            override fun next(): Any? = v.items[i++]
        }
        is PyTuple -> v.items.iterator()
        is String -> {
            val points = v.codePoints().toArray()
            points.map { String(Character.toChars(it)) }.iterator()
        }
        is PyDict -> ArrayList(v.map.keys).iterator()
        is PySet -> ArrayList(v.items).iterator()
        is PyBytes -> v.data.map { (it.toInt() and 0xFF).toLong() }.iterator()
        is PyRange -> object : Iterator<Any?> {
            var i = 0L
            override fun hasNext() = i < v.size
            override fun next(): Any? = v.at(i++)
        }
        is PyGenerator -> v
        is PyIterator -> v
        is PyInstance -> {
            val iterator = interp().callSpecial(v, "__iter__", emptyList())
            when {
                iterator === Missing -> throw error("TypeError", "'${typeName(v)}' object is not iterable")
                iterator is PyInstance -> object : Iterator<Any?> {
                    var buffered: Any? = Missing
                    var done = false
                    private fun fill() {
                        if (buffered !== Missing || done) return
                        try {
                            buffered = interp().callSpecial(iterator, "__next__", emptyList()).also { if (it === Missing) throw error("TypeError", "iter() returned non-iterator of type '${typeName(iterator)}'") }
                        } catch (e: PyError) {
                            if (e.value.cls.isSubclassOf(Builtins.exceptionClass("StopIteration"))) done = true else throw e
                        }
                    }
                    override fun hasNext(): Boolean { fill(); return !done }
                    override fun next(): Any? { fill(); if (done) throw NoSuchElementException(); return buffered.also { buffered = Missing } }
                }
                else -> iterate(iterator)
            }
        }
        else -> throw error("TypeError", "'${typeName(v)}' object is not iterable")
    }

    fun toList(v: Any?): MutableList<Any?> = when (v) {
        is PyList -> ArrayList(v.items)
        is PyTuple -> ArrayList(v.items)
        else -> ArrayList<Any?>().also { out -> val it = iterate(v); while (it.hasNext()) out += it.next() }
    }

    private fun index(i: Long, size: Int, what: String): Int {
        val k = if (i < 0) i + size else i
        if (k < 0 || k >= size) throw error("IndexError", "$what index out of range")
        return k.toInt()
    }

    /** Positions selected by a slice of a sequence of [size] items. */
    fun sliceIndices(slice: PySlice, size: Int): List<Int> {
        val step = if (slice.step == null || slice.step === PyNone) 1L else toLong(slice.step)
        if (step == 0L) throw error("ValueError", "slice step cannot be zero")
        fun bound(v: Any?, default: Long, low: Long, high: Long): Long {
            if (v == null || v === PyNone) return default
            var n = toLong(v)
            if (n < 0) n += size
            return n.coerceIn(low, high)
        }
        val out = ArrayList<Int>()
        if (step > 0) {
            var i = bound(slice.lo, 0, 0, size.toLong())
            val end = bound(slice.hi, size.toLong(), 0, size.toLong())
            while (i < end) { out += i.toInt(); i += step }
        } else {
            var i = bound(slice.lo, size - 1L, -1, size - 1L)
            val end = bound(slice.hi, -1, -1, size - 1L)
            while (i > end) { out += i.toInt(); i += step }
        }
        return out
    }

    fun getItem(obj: Any?, key: Any?): Any? = when (obj) {
        is PyList -> if (key is PySlice) PyList(sliceIndices(key, obj.items.size).map { obj.items[it] }.toMutableList()) else obj.items[index(toLong(key), obj.items.size, "list")]
        is PyTuple -> if (key is PySlice) PyTuple(sliceIndices(key, obj.items.size).map { obj.items[it] }) else obj.items[index(toLong(key), obj.items.size, "tuple")]
        is String -> {
            val points = obj.codePoints().toArray()
            if (key is PySlice) String(sliceIndices(key, points.size).map { points[it] }.toIntArray(), 0, sliceIndices(key, points.size).size)
            else String(Character.toChars(points[index(toLong(key), points.size, "string")]))
        }
        is PyDict -> {
            val k = keyOf(key)
            when {
                obj.map.containsKey(k) -> obj.map[k]
                obj.factory != null -> call(obj.factory, emptyList()).also { obj.map[k] = it }
                else -> throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf(key)))))
            }
        }
        is PyBytes -> if (key is PySlice) PyBytes(sliceIndices(key, obj.data.size).map { obj.data[it] }.toByteArray()) else (obj.data[index(toLong(key), obj.data.size, "bytes")].toInt() and 0xFF).toLong()
        is PyRange -> obj.at(index(toLong(key), obj.size.toInt(), "range").toLong())
        is PyInstance -> {
            val r = interp().callSpecial(obj, "__getitem__", listOf(key))
            if (r === Missing) throw error("TypeError", "'${typeName(obj)}' object is not subscriptable") else r
        }
        is PyObject -> obj.attrs["__getitem__"]?.let { call(it, listOf(key)) } ?: throw error("TypeError", "'${obj.typeName}' object is not subscriptable")
        else -> throw error("TypeError", "'${typeName(obj)}' object is not subscriptable")
    }

    fun setItem(obj: Any?, key: Any?, value: Any?) {
        when (obj) {
            is PyList -> if (key is PySlice) {
                val positions = sliceIndices(key, obj.items.size)
                val replacement = toList(value)
                if (key.step == null || key.step === PyNone || toLong(key.step) == 1L) {
                    val start = positions.firstOrNull() ?: (if (key.lo == null || key.lo === PyNone) 0 else toLong(key.lo).let { if (it < 0) (it + obj.items.size).coerceAtLeast(0) else it.coerceAtMost(obj.items.size.toLong()) }.toInt())
                    repeat(positions.size) { obj.items.removeAt(start) }
                    obj.items.addAll(start, replacement)
                } else {
                    if (replacement.size != positions.size) throw error("ValueError", "attempt to assign sequence of size ${replacement.size} to extended slice of size ${positions.size}")
                    positions.forEachIndexed { n, p -> obj.items[p] = replacement[n] }
                }
            } else obj.items[index(toLong(key), obj.items.size, "list assignment")] = value
            is PyDict -> obj.map[keyOf(key)] = value
            is PyInstance -> if (interp().callSpecial(obj, "__setitem__", listOf(key, value)) === Missing) throw error("TypeError", "'${typeName(obj)}' object does not support item assignment")
            else -> throw error("TypeError", "'${typeName(obj)}' object does not support item assignment")
        }
    }

    fun delItem(obj: Any?, key: Any?) {
        when (obj) {
            is PyList -> if (key is PySlice) sliceIndices(key, obj.items.size).sortedDescending().forEach { obj.items.removeAt(it) } else obj.items.removeAt(index(toLong(key), obj.items.size, "list assignment"))
            is PyDict -> {
                val k = keyOf(key)
                if (!obj.map.containsKey(k)) throw PyError(PyInstance(Builtins.exceptionClass("KeyError"), linkedMapOf("args" to PyTuple(listOf(key)))))
                obj.map.remove(k)
            }
            is PyInstance -> if (interp().callSpecial(obj, "__delitem__", listOf(key)) === Missing) throw error("TypeError", "'${typeName(obj)}' object doesn't support item deletion")
            else -> throw error("TypeError", "'${typeName(obj)}' object doesn't support item deletion")
        }
    }

    // --- attributes and calls -------------------------------------------------------------------

    fun getAttr(obj: Any?, name: String): Any? {
        val found = lookupAttr(obj, name)
        if (found === Missing) throw error("AttributeError", "'${typeName(obj)}' object has no attribute '$name'")
        return found
    }

    fun lookupAttr(obj: Any?, name: String): Any? = interp().lookupAttr(obj, name)

    fun setAttr(obj: Any?, name: String, value: Any?) = interp().setAttr(obj, name, value)

    fun call(fn: Any?, args: List<Any?>, kwargs: Map<String, Any?> = emptyMap()): Any? = interp().call(fn, args, kwargs)

    // --- number conversion ----------------------------------------------------------------------

    fun parseInt(text: String, base: Int = 10): Any {
        var s = text.trim().replace("_", "")
        var radix = base
        val negative = s.startsWith("-")
        if (s.startsWith("-") || s.startsWith("+")) s = s.substring(1)
        if (radix == 16 && (s.startsWith("0x") || s.startsWith("0X"))) s = s.substring(2)
        if (radix == 2 && (s.startsWith("0b") || s.startsWith("0B"))) s = s.substring(2)
        if (radix == 8 && (s.startsWith("0o") || s.startsWith("0O"))) s = s.substring(2)
        if (radix == 0) {
            radix = when {
                s.startsWith("0x", true) -> 16.also { s = s.substring(2) }
                s.startsWith("0o", true) -> 8.also { s = s.substring(2) }
                s.startsWith("0b", true) -> 2.also { s = s.substring(2) }
                else -> 10
            }
        }
        val value = try {
            BigInteger(s, radix)
        } catch (e: NumberFormatException) {
            throw error("ValueError", "invalid literal for int() with base $base: ${stringRepr(text)}")
        }
        return norm(if (negative) value.negate() else value)
    }

    fun roundHalfEven(d: Double, digits: Int): Double =
        if (d.isNaN() || d.isInfinite()) d else BigDecimal(d).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}

/** `NotImplemented`, returned by special methods that do not handle the operand. */
internal object NotImplementedValue
