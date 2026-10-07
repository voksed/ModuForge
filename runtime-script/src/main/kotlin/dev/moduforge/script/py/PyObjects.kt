package dev.moduforge.script.py

import java.math.BigInteger

/** `None`. */
internal object PyNone {
    override fun toString() = "None"
}

/** Stands for "no such attribute" or "no such special method" without raising. */
internal object Missing

internal class PyList(val items: MutableList<Any?> = ArrayList())

internal class PyTuple(val items: List<Any?>) {
    override fun equals(other: Any?) = other is PyTuple && items == other.items
    override fun hashCode() = items.hashCode()
}

/** Keys are kept in the normal form of [keyOf]; [factory] makes a `defaultdict`. */
internal class PyDict(val map: LinkedHashMap<Any?, Any?> = LinkedHashMap(), var factory: Any? = null)

internal class PySet(val items: LinkedHashSet<Any?> = LinkedHashSet(), val frozen: Boolean = false)

internal class PyBytes(val data: ByteArray) {
    override fun equals(other: Any?) = other is PyBytes && data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()
}

internal class PySlice(val lo: Any?, val hi: Any?, val step: Any?)

internal class PyRange(val start: Long, val stop: Long, val step: Long) {
    val size: Long
        get() = when {
            step > 0 -> if (stop > start) (stop - start + step - 1) / step else 0
            else -> if (start > stop) (start - stop - step - 1) / -step else 0
        }

    fun at(index: Long): Long = start + index * step

    override fun equals(other: Any?) = other is PyRange && size == other.size && (size == 0L || (start == other.start && step == other.step))
    override fun hashCode() = size.hashCode()
}

/** A function defined in the script. */
internal class PyFunction(
    val def: FuncDef,
    val closure: Scope,
    /** Defaults by parameter name, evaluated when the function was defined. */
    val defaults: Map<String, Any?>,
    var owner: PyClass? = null,
) {
    val name: String get() = def.name
}

/** A function implemented by the runtime. */
internal class PyBuiltin(val name: String, val fn: (List<Any?>, Map<String, Any?>) -> Any?)

internal class BoundMethod(val self: Any?, val fn: Any?)

internal class PyStaticMethod(val fn: Any?)
internal class PyClassMethod(val fn: Any?)
internal class PyProperty(val getter: Any?, val setter: Any? = null)

/**
 * A class. Classes of the runtime itself (`int`, `str`, exceptions…) have a [ctor]; classes of
 * the script are built from their body.
 */
internal class PyClass(
    val name: String,
    val bases: List<PyClass>,
    val attrs: MutableMap<String, Any?> = LinkedHashMap(),
    val ctor: ((List<Any?>, Map<String, Any?>) -> Any?)? = null,
) {
    val mro: List<PyClass> by lazy { linearize() }

    private fun linearize(): List<PyClass> {
        val sequences = bases.map { it.mro.toMutableList() }.toMutableList()
        sequences += bases.toMutableList()
        val result = arrayListOf(this)
        while (sequences.any { it.isNotEmpty() }) {
            val candidate = sequences.filter { it.isNotEmpty() }.map { it.first() }
                .firstOrNull { head -> sequences.none { seq -> seq.indexOf(head) > 0 } }
                ?: throw PyError.of("TypeError", "cannot create a consistent method resolution order")
            result += candidate
            sequences.forEach { if (it.isNotEmpty() && it.first() === candidate) it.removeAt(0) }
        }
        return result
    }

    fun lookup(name: String): Any? {
        for (cls in mro) if (name in cls.attrs) return cls.attrs[name]
        return Missing
    }

    fun isSubclassOf(other: PyClass): Boolean = mro.any { it === other }

    /** The runtime class this class inherits its construction from, if any. */
    fun nativeBase(): PyClass? = mro.firstOrNull { it.ctor != null }
}

internal class PyInstance(val cls: PyClass, val attrs: MutableMap<String, Any?> = LinkedHashMap())

internal class PyModule(val name: String, val attrs: MutableMap<String, Any?> = LinkedHashMap())

/** Something with attributes made by the host: a response, a socket. */
internal class PyObject(val typeName: String, val attrs: MutableMap<String, Any?> = LinkedHashMap())

/** The exception raised in a script. [value] is an instance of a subclass of `BaseException`. */
internal class PyError(val value: PyInstance) : RuntimeException(null, null, false, false) {
    var file: String = ""
    var line: Int = 0

    val typeName: String get() = value.cls.name

    val text: String
        get() {
            val args = (value.attrs["args"] as? PyTuple)?.items.orEmpty()
            return when (args.size) {
                0 -> ""
                1 -> Py.str(args[0])
                else -> Py.repr(PyTuple(args))
            }
        }

    override val message: String get() = if (text.isEmpty()) typeName else "$typeName: $text"

    companion object {
        /** Raises the built-in exception [type] with [message]. */
        fun of(type: String, message: String): PyError {
            val cls = Builtins.exceptionClass(type)
            return PyError(PyInstance(cls, linkedMapOf("args" to PyTuple(listOf(message)))))
        }
    }
}

/** A running `return`, `break` or `continue`. */
internal object BreakSignal : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = BreakSignal
}

internal object ContinueSignal : RuntimeException(null, null, false, false) {
    private fun readResolve(): Any = ContinueSignal
}

internal class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)

/** The script was asked to stop. */
internal class PyInterrupted : RuntimeException(null, null, false, false)

/** `sys.exit()`. */
internal class PyExit(val code: Any?) : RuntimeException(null, null, false, false)

/** Variables of a module, a function call, a class body or a comprehension. */
internal class Scope(val parent: Scope?, val isClass: Boolean = false) {
    val vars = HashMap<String, Any?>()
    var globalNames: MutableSet<String>? = null
    var nonlocalNames: MutableSet<String>? = null
    var function: PyFunction? = null

    /** Name of the source file, kept on the root scope of a module. */
    var file: String = ""

    val root: Scope get() = generateSequence(this) { it.parent }.last()
}

/** Normal form of a dict key or set member: `True` and `1`, `2.0` and `2` are the same key. */
internal fun keyOf(v: Any?): Any? = when (v) {
    is Boolean -> if (v) 1L else 0L
    is Int -> v.toLong()
    is Double -> if (v % 1.0 == 0.0 && Math.abs(v) < 9.0e15) v.toLong() else v
    is BigInteger -> if (v.bitLength() < 64) v.toLong() else v
    is PyList, is PyDict, is PySet -> if (v is PySet && v.frozen) v else throw PyError.of("TypeError", "unhashable type: '${Builtins.typeName(v)}'")
    is PyTuple -> PyTuple(v.items.map(::keyOf))
    else -> v
}
