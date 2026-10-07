package dev.moduforge.script.py

import java.math.BigInteger

/** What the interpreter needs from the host: output, the files of the module, bundled libraries. */
internal interface PyHost {
    fun log(text: String)

    /** Source of a file of the module by path, or null. */
    fun file(path: String): String?

    /** Source of a library shipped with the runtime, e.g. `mf/config.py`, or null. */
    fun bundled(path: String): String?

    /** The module `mf` that exposes the host's services. */
    fun hostModule(interpreter: Interpreter): PyModule

    fun ask(question: String): String?
}

/** Executes parsed Python: statements, expressions, calls, classes, imports. */
internal class Interpreter(val host: PyHost) {

    class Frame(val function: PyFunction?, val self: Any?)

    /** Per-thread bookkeeping: the call stack and the exception being handled. */
    class ThreadState {
        val frames = ArrayList<Frame>()
        val handling = ArrayList<PyError>()
    }

    private val states = ThreadLocal.withInitial { ThreadState() }
    private val state: ThreadState get() = states.get()

    val builtins: MutableMap<String, Any?> = Builtins.globals(this)
    private val modules = HashMap<String, PyModule>()
    val mainScope = Scope(null).also { it.vars["__name__"] = "__main__" }

    companion object {
        private val current = ThreadLocal<Interpreter>()
        private const val MAX_DEPTH = 450

        fun current(): Interpreter = current.get() ?: throw IllegalStateException("no Python interpreter on this thread")

        fun enter(interpreter: Interpreter) = current.set(interpreter)
    }

    // --- output -----------------------------------------------------------------------------

    private val pending = StringBuilder()

    /** Standard output: complete lines go to the module output, a partial line waits for its end. */
    fun write(text: String) {
        synchronized(pending) {
            pending.append(text)
            while (true) {
                val end = pending.indexOf('\n')
                if (end < 0) break
                host.log(pending.substring(0, end))
                pending.delete(0, end + 1)
            }
        }
    }

    /** Writes out a line that was left unfinished, e.g. by `print("x", end="")`. */
    fun flushOutput() {
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                host.log(pending.toString())
                pending.setLength(0)
            }
        }
    }

    // --- running ----------------------------------------------------------------------------

    /** Runs the main script. Errors surface as [PyError] with file and line set. */
    fun runMain(source: String, fileName: String) {
        enter(this)
        mainScope.file = fileName
        val program = try {
            PyParser(source).parseModule()
        } catch (e: PySyntaxError) {
            throw PyError.of("SyntaxError", e.message ?: "invalid syntax").also {
                it.file = fileName
                it.line = e.line
            }
        }
        execBlock(program, mainScope)
    }

    private fun checkInterrupt() {
        if (Thread.currentThread().isInterrupted) throw PyInterrupted()
    }

    // --- statements -------------------------------------------------------------------------

    fun execBlock(stmts: List<Stmt>, scope: Scope) {
        for (stmt in stmts) {
            try {
                exec(stmt, scope)
            } catch (e: PyError) {
                if (e.line == 0) {
                    e.line = stmt.line
                    e.file = scope.root.file
                }
                throw e
            } catch (e: StackOverflowError) {
                throw PyError.of("RecursionError", "maximum recursion depth exceeded").also {
                    it.line = stmt.line
                    it.file = scope.root.file
                }
            }
        }
    }

    private fun exec(stmt: Stmt, scope: Scope) {
        when (stmt) {
            is ExprS -> eval(stmt.expr, scope)
            is AssignS -> {
                val value = eval(stmt.value, scope)
                for (target in stmt.targets) assignTo(target, value, scope)
            }
            is AugAssignS -> augAssign(stmt, scope)
            is IfS -> if (Py.truth(eval(stmt.cond, scope))) execBlock(stmt.body, scope) else execBlock(stmt.otherwise, scope)
            is WhileS -> whileLoop(stmt, scope)
            is ForS -> forLoop(stmt, scope)
            is TryS -> tryStatement(stmt, scope)
            is WithS -> withStatement(stmt, 0, scope)
            is FuncS -> {
                var fn: Any? = makeFunction(stmt.def, scope)
                for (decorator in stmt.decorators.asReversed()) fn = call(eval(decorator, scope), listOf(fn))
                assign(scope, stmt.def.name, fn)
            }
            is ClassS -> classStatement(stmt, scope)
            is ReturnS -> throw ReturnSignal(stmt.value?.let { eval(it, scope) } ?: PyNone)
            is PassS -> Unit
            is BreakS -> throw BreakSignal
            is ContinueS -> throw ContinueSignal
            is RaiseS -> raise(stmt, scope)
            is AssertS -> if (!Py.truth(eval(stmt.cond, scope))) {
                val message = stmt.message?.let { eval(it, scope) }
                throw PyError(instantiateException("AssertionError", if (message == null) emptyList() else listOf(message)))
            }
            is DelS -> stmt.targets.forEach { delete(it, scope) }
            is GlobalS -> (scope.globalNames ?: HashSet<String>().also { scope.globalNames = it }).addAll(stmt.names)
            is NonlocalS -> (scope.nonlocalNames ?: HashSet<String>().also { scope.nonlocalNames = it }).addAll(stmt.names)
            is ImportS -> importStatement(stmt, scope)
            is ImportFromS -> importFrom(stmt, scope)
        }
    }

    private fun augAssign(stmt: AugAssignS, scope: Scope) {
        val target = stmt.target
        when (target) {
            is NameE -> assign(scope, target.id, inplace(stmt.op, lookup(scope, target.id), eval(stmt.value, scope)))
            is AttrE -> {
                val obj = eval(target.obj, scope)
                Py.setAttr(obj, target.name, inplace(stmt.op, Py.getAttr(obj, target.name), eval(stmt.value, scope)))
            }
            is SubE -> {
                val obj = eval(target.obj, scope)
                val index = eval(target.index, scope)
                Py.setItem(obj, index, inplace(stmt.op, Py.getItem(obj, index), eval(stmt.value, scope)))
            }
            else -> throw Py.error("SyntaxError", "illegal expression for augmented assignment")
        }
    }

    /** `a += b`: lists and sets change in place, everything else is `a = a + b`. */
    private fun inplace(op: String, current: Any?, value: Any?): Any? {
        if (op == "+" && current is PyList) {
            current.items.addAll(Py.toList(value))
            return current
        }
        if (current is PySet && !current.frozen && value is PySet) {
            when (op) {
                "|" -> { current.items.addAll(value.items); return current }
                "&" -> { current.items.retainAll(value.items); return current }
                "-" -> { current.items.removeAll(value.items); return current }
            }
        }
        if (current is PyInstance) {
            val special = mapOf("+" to "__iadd__", "-" to "__isub__", "*" to "__imul__")[op]
            if (special != null) {
                val r = callSpecial(current, special, listOf(value))
                if (r !== Missing && r !== NotImplementedValue) return r
            }
        }
        return Py.binary(op, current, value)
    }

    private fun whileLoop(stmt: WhileS, scope: Scope) {
        var broke = false
        while (Py.truth(eval(stmt.cond, scope))) {
            checkInterrupt()
            try {
                execBlock(stmt.body, scope)
            } catch (e: BreakSignal) {
                broke = true
                break
            } catch (e: ContinueSignal) {
                continue
            }
        }
        if (!broke) execBlock(stmt.otherwise, scope)
    }

    private fun forLoop(stmt: ForS, scope: Scope) {
        val iterator = Py.iterate(eval(stmt.iter, scope))
        var broke = false
        while (iterator.hasNext()) {
            checkInterrupt()
            assignTo(stmt.target, iterator.next(), scope)
            try {
                execBlock(stmt.body, scope)
            } catch (e: BreakSignal) {
                broke = true
                break
            } catch (e: ContinueSignal) {
                continue
            }
        }
        if (!broke) execBlock(stmt.otherwise, scope)
    }

    private fun tryStatement(stmt: TryS, scope: Scope) {
        try {
            try {
                execBlock(stmt.body, scope)
            } catch (e: PyError) {
                val handler = stmt.handlers.firstOrNull { matches(e, it, scope) } ?: throw e
                handler.name?.let { assign(scope, it, e.value) }
                state.handling.add(e)
                try {
                    execBlock(handler.body, scope)
                } finally {
                    state.handling.removeAt(state.handling.lastIndex)
                    handler.name?.let { scope.vars.remove(it) }
                }
                return
            } catch (e: StackOverflowError) {
                val error = PyError.of("RecursionError", "maximum recursion depth exceeded")
                val handler = stmt.handlers.firstOrNull { matches(error, it, scope) } ?: throw e
                execBlock(handler.body, scope)
                return
            }
            execBlock(stmt.otherwise, scope)
        } finally {
            if (stmt.finally.isNotEmpty()) execBlock(stmt.finally, scope)
        }
    }

    private fun matches(error: PyError, handler: Handler, scope: Scope): Boolean {
        val type = handler.type ?: return true
        val spec = eval(type, scope)
        val classes = if (spec is PyTuple) spec.items else listOf(spec)
        return classes.any { c -> c is PyClass && error.value.cls.isSubclassOf(c) }
    }

    private fun withStatement(stmt: WithS, index: Int, scope: Scope) {
        if (index >= stmt.items.size) {
            execBlock(stmt.body, scope)
            return
        }
        val (contextExpr, target) = stmt.items[index]
        val manager = eval(contextExpr, scope)
        val enter = Py.lookupAttr(manager, "__enter__").takeIf { it !== Missing } ?: throw Py.error("AttributeError", "__enter__")
        val exit = Py.lookupAttr(manager, "__exit__").takeIf { it !== Missing } ?: throw Py.error("AttributeError", "__exit__")
        val entered = call(enter, emptyList())
        if (target != null) assignTo(target, entered, scope)
        try {
            withStatement(stmt, index + 1, scope)
        } catch (e: PyError) {
            if (!Py.truth(call(exit, listOf(e.value.cls, e.value, PyNone)))) throw e
            return
        } catch (e: RuntimeException) {
            call(exit, listOf(PyNone, PyNone, PyNone))
            throw e
        }
        call(exit, listOf(PyNone, PyNone, PyNone))
    }

    private fun raise(stmt: RaiseS, scope: Scope) {
        val expression = stmt.exc
        if (expression == null) {
            throw state.handling.lastOrNull() ?: Py.error("RuntimeError", "No active exception to reraise")
        }
        val value = eval(expression, scope)
        val instance = when {
            value is PyClass -> call(value, emptyList()) as? PyInstance
            else -> value as? PyInstance
        }
        if (instance == null || !instance.cls.isSubclassOf(Builtins.exceptionClass("BaseException"))) {
            throw Py.error("TypeError", "exceptions must derive from BaseException")
        }
        stmt.cause?.let { cause -> instance.attrs["__cause__"] = eval(cause, scope) }
        throw PyError(instance)
    }

    fun instantiateException(type: String, args: List<Any?>): PyInstance =
        PyInstance(Builtins.exceptionClass(type), linkedMapOf("args" to PyTuple(args)))

    private fun classStatement(stmt: ClassS, scope: Scope) {
        val bases = stmt.bases.map {
            eval(it, scope) as? PyClass ?: throw Py.error("TypeError", "a base of a class must be a class")
        }.ifEmpty { listOf(Builtins.OBJECT) }
        val native = bases.firstNotNullOfOrNull { b -> b.mro.firstOrNull { it.ctor != null && it !== Builtins.OBJECT } }
        if (native != null) throw Py.error("TypeError", "a class cannot inherit from the built-in type '${native.name}'")
        val body = Scope(scope, isClass = true)
        body.vars["__name__"] = stmt.name
        execBlock(stmt.body, body)
        val cls = PyClass(stmt.name, bases, LinkedHashMap(body.vars))
        for (value in cls.attrs.values) {
            val function = when (value) {
                is PyFunction -> value
                is PyStaticMethod -> value.fn as? PyFunction
                is PyClassMethod -> value.fn as? PyFunction
                is PyProperty -> value.getter as? PyFunction
                else -> null
            }
            function?.owner = cls
            ((value as? PyProperty)?.setter as? PyFunction)?.owner = cls
        }
        var result: Any? = cls
        for (decorator in stmt.decorators.asReversed()) result = call(eval(decorator, scope), listOf(result))
        assign(scope, stmt.name, result)
    }

    // --- names and assignment ---------------------------------------------------------------

    fun lookup(scope: Scope, name: String): Any? {
        var s: Scope? = scope
        var first = true
        while (s != null) {
            if (!s.isClass || first) {
                val value = s.vars[name]
                if (value != null) return value
            }
            first = false
            s = s.parent
        }
        builtins[name]?.let { return it }
        throw Py.error("NameError", "name '$name' is not defined")
    }

    fun assign(scope: Scope, name: String, value: Any?) {
        val globals = scope.globalNames
        if (globals != null && name in globals) {
            scope.root.vars[name] = value
            return
        }
        val nonlocals = scope.nonlocalNames
        if (nonlocals != null && name in nonlocals) {
            var s = scope.parent
            while (s != null && s.parent != null) {
                if (!s.isClass && name in s.vars) {
                    s.vars[name] = value
                    return
                }
                s = s.parent
            }
            throw Py.error("SyntaxError", "no binding for nonlocal '$name' found")
        }
        scope.vars[name] = value
    }

    fun assignTo(target: Expr, value: Any?, scope: Scope) {
        when (target) {
            is NameE -> assign(scope, target.id, value)
            is AttrE -> Py.setAttr(eval(target.obj, scope), target.name, value)
            is SubE -> Py.setItem(eval(target.obj, scope), eval(target.index, scope), value)
            is TupleE -> unpack(target.items, value, scope)
            is ListE -> unpack(target.items, value, scope)
            is StarE -> assignTo(target.value, PyList(Py.toList(value)), scope)
            else -> throw Py.error("SyntaxError", "cannot assign to this expression")
        }
    }

    private fun unpack(targets: List<Expr>, value: Any?, scope: Scope) {
        val items = Py.toList(value)
        val star = targets.indexOfFirst { it is StarE }
        if (star < 0) {
            if (items.size != targets.size) {
                throw Py.error("ValueError", if (items.size > targets.size) "too many values to unpack (expected ${targets.size})" else "not enough values to unpack (expected ${targets.size}, got ${items.size})")
            }
            targets.forEachIndexed { i, t -> assignTo(t, items[i], scope) }
            return
        }
        val after = targets.size - star - 1
        if (items.size < targets.size - 1) throw Py.error("ValueError", "not enough values to unpack (expected at least ${targets.size - 1}, got ${items.size})")
        for (i in 0 until star) assignTo(targets[i], items[i], scope)
        assignTo((targets[star] as StarE).value, PyList(items.subList(star, items.size - after).toMutableList()), scope)
        for (i in 0 until after) assignTo(targets[star + 1 + i], items[items.size - after + i], scope)
    }

    private fun delete(target: Expr, scope: Scope) {
        when (target) {
            is NameE -> if (scope.vars.remove(target.id) == null) throw Py.error("NameError", "name '${target.id}' is not defined")
            is AttrE -> {
                val obj = eval(target.obj, scope)
                val attrs = when (obj) { is PyInstance -> obj.attrs; is PyModule -> obj.attrs; is PyClass -> obj.attrs; else -> null }
                if (attrs == null || attrs.remove(target.name) == null) throw Py.error("AttributeError", target.name)
            }
            is SubE -> Py.delItem(eval(target.obj, scope), eval(target.index, scope))
            is TupleE -> target.items.forEach { delete(it, scope) }
            else -> throw Py.error("SyntaxError", "cannot delete this expression")
        }
    }

    // --- expressions ------------------------------------------------------------------------

    fun eval(e: Expr, scope: Scope): Any? = when (e) {
        is Const -> e.value
        is NameE -> lookup(scope, e.id)
        is AttrE -> {
            val obj = eval(e.obj, scope)
            Py.getAttr(obj, e.name)
        }
        is SubE -> Py.getItem(eval(e.obj, scope), eval(e.index, scope))
        is SliceE -> PySlice(e.lo?.let { eval(it, scope) }, e.hi?.let { eval(it, scope) }, e.step?.let { eval(it, scope) })
        is CallE -> evalCall(e, scope)
        is BinE -> Py.binary(e.op, eval(e.left, scope), eval(e.right, scope))
        is UnaryE -> if (e.op == "not") !Py.truth(eval(e.operand, scope)) else Py.unary(e.op, eval(e.operand, scope))
        is BoolE -> {
            val left = eval(e.left, scope)
            if (e.isAnd) (if (Py.truth(left)) eval(e.right, scope) else left) else (if (Py.truth(left)) left else eval(e.right, scope))
        }
        is CompareE -> compare(e, scope)
        is IfE -> if (Py.truth(eval(e.cond, scope))) eval(e.then, scope) else eval(e.otherwise, scope)
        is LambdaE -> makeFunction(e.def, scope)
        is ListE -> PyList(spread(e.items, scope))
        is TupleE -> PyTuple(spread(e.items, scope))
        is SetE -> PySet(LinkedHashSet<Any?>().also { out -> spread(e.items, scope).forEach { out += keyOf(it) } })
        is DictE -> evalDict(e, scope)
        is StarE -> throw Py.error("SyntaxError", "can't use starred expression here")
        is YieldE -> yieldValue(e.value?.let { eval(it, scope) } ?: PyNone, scope)
        is YieldFromE -> {
            val iterator = Py.iterate(eval(e.value, scope))
            while (iterator.hasNext()) yieldValue(iterator.next(), scope)
            PyNone
        }
        is WalrusE -> eval(e.value, scope).also { assign(scope, e.name, it) }
        is FStringE -> fstring(e.parts, scope)
        is CompE -> comprehension(e, scope)
    }

    private fun spread(items: List<Expr>, scope: Scope): MutableList<Any?> {
        val out = ArrayList<Any?>()
        for (item in items) if (item is StarE) out.addAll(Py.toList(eval(item.value, scope))) else out += eval(item, scope)
        return out
    }

    private fun evalDict(e: DictE, scope: Scope): PyDict {
        val dict = PyDict()
        for ((key, value) in e.entries) {
            if (key == null) {
                val other = eval(value, scope) as? PyDict ?: throw Py.error("TypeError", "'**' needs a mapping")
                dict.map.putAll(other.map)
            } else {
                dict.map[keyOf(eval(key, scope))] = eval(value, scope)
            }
        }
        return dict
    }

    private fun compare(e: CompareE, scope: Scope): Any? {
        var left = eval(e.first, scope)
        for ((op, rightExpr) in e.ops) {
            val right = eval(rightExpr, scope)
            if (!Py.compareOp(op, left, right)) return false
            left = right
        }
        return true
    }

    private fun fstring(parts: List<FPart>, scope: Scope): String {
        val sb = StringBuilder()
        for (part in parts) {
            when {
                part.text != null -> sb.append(part.text)
                part.expr is FStringE -> sb.append(fstring(part.expr.parts, scope))
                else -> {
                    var value = eval(part.expr!!, scope)
                    value = when (part.conversion) {
                        'r', 'a' -> Py.repr(value)
                        's' -> Py.str(value)
                        else -> value
                    }
                    val spec = part.spec?.let { fstring(it, scope) }.orEmpty()
                    sb.append(Formatting.format(value, spec))
                }
            }
        }
        return sb.toString()
    }

    private fun comprehension(e: CompE, scope: Scope): Any? {
        val inner = Scope(scope)
        if (e.kind == "gen") {
            // Evaluated lazily, like in Python, so that infinite sources work.
            val results = PyGenerator(this) { gen ->
                runFors(e, 0, inner) { gen.yieldValue(eval(e.element, inner)) }
                PyNone
            }
            return results
        }
        val list = ArrayList<Any?>()
        val dict = if (e.kind == "dict") PyDict() else null
        runFors(e, 0, inner) {
            if (dict != null) dict.map[keyOf(eval(e.element, inner))] = eval(e.value!!, inner) else list += eval(e.element, inner)
        }
        return when (e.kind) {
            "list" -> PyList(list)
            "set" -> PySet(LinkedHashSet<Any?>().also { out -> list.forEach { out += keyOf(it) } })
            else -> dict
        }
    }

    private fun runFors(e: CompE, level: Int, scope: Scope, emit: () -> Unit) {
        if (level == e.fors.size) {
            emit()
            return
        }
        val clause = e.fors[level]
        val iterator = Py.iterate(eval(clause.iter, if (level == 0) scope.parent!! else scope))
        while (iterator.hasNext()) {
            checkInterrupt()
            assignTo(clause.target, iterator.next(), scope)
            if (clause.conds.all { Py.truth(eval(it, scope)) }) runFors(e, level + 1, scope, emit)
        }
    }

    private fun yieldValue(value: Any?, scope: Scope): Any? {
        val generator = currentGenerator.get() ?: throw Py.error("SyntaxError", "'yield' outside a generator")
        return generator.yieldValue(value)
    }

    /** The generator whose body runs on this thread. */
    private val currentGenerator = ThreadLocal<PyGenerator>()

    // --- calls ------------------------------------------------------------------------------

    private fun evalCall(e: CallE, scope: Scope): Any? {
        val fn = eval(e.fn, scope)
        val args = ArrayList<Any?>()
        var kwargs: MutableMap<String, Any?>? = null
        for (arg in e.args) {
            when {
                arg.star == 1 -> args.addAll(Py.toList(eval(arg.value, scope)))
                arg.star == 2 -> {
                    val mapping = eval(arg.value, scope) as? PyDict ?: throw Py.error("TypeError", "argument after ** must be a mapping")
                    val target = kwargs ?: LinkedHashMap<String, Any?>().also { kwargs = it }
                    for ((k, v) in mapping.map) target[k as? String ?: throw Py.error("TypeError", "keywords must be strings")] = v
                }
                arg.name != null -> (kwargs ?: LinkedHashMap<String, Any?>().also { kwargs = it })[arg.name] = eval(arg.value, scope)
                else -> args += eval(arg.value, scope)
            }
        }
        return try {
            call(fn, args, kwargs ?: emptyMap())
        } catch (error: PyError) {
            if (error.line == 0) {
                error.line = e.line
                error.file = scope.root.file
            }
            throw error
        }
    }

    fun call(fn: Any?, args: List<Any?>, kwargs: Map<String, Any?> = emptyMap()): Any? = when (fn) {
        is PyFunction -> callFunction(fn, args, kwargs)
        is PyBuiltin -> fn.fn(args, kwargs)
        is BoundMethod -> call(fn.fn, listOf(fn.self) + args, kwargs)
        is PyClass -> instantiate(fn, args, kwargs)
        is PyStaticMethod -> call(fn.fn, args, kwargs)
        is PyInstance -> {
            val r = callSpecial(fn, "__call__", args, kwargs)
            if (r === Missing) throw Py.error("TypeError", "'${fn.cls.name}' object is not callable") else r
        }
        else -> throw Py.error("TypeError", "'${Py.typeName(fn)}' object is not callable")
    }

    private fun instantiate(cls: PyClass, args: List<Any?>, kwargs: Map<String, Any?>): Any? {
        cls.ctor?.let { return it(args, kwargs) }
        val instance = PyInstance(cls)
        val init = cls.lookup("__init__")
        if (init !== Missing) {
            val result = call(init, listOf(instance) + args, kwargs)
            if (result !== PyNone) throw Py.error("TypeError", "__init__() should return None, not '${Py.typeName(result)}'")
        } else if (args.isNotEmpty() || kwargs.isNotEmpty()) {
            throw Py.error("TypeError", "${cls.name}() takes no arguments")
        }
        return instance
    }

    /** Calls the special method [name] of [obj]'s class, or returns [Missing] when the class has none. */
    fun callSpecial(obj: PyInstance, name: String, args: List<Any?>, kwargs: Map<String, Any?> = emptyMap()): Any? {
        val fn = obj.cls.lookup(name)
        if (fn === Missing) return Missing
        return call(fn, listOf(obj) + args, kwargs)
    }

    private fun makeFunction(def: FuncDef, scope: Scope): PyFunction {
        val defaults = HashMap<String, Any?>()
        for (p in def.params) p.default?.let { defaults[p.name] = eval(it, scope) }
        return PyFunction(def, scope, defaults)
    }

    private fun callFunction(f: PyFunction, args: List<Any?>, kwargs: Map<String, Any?>): Any? {
        val st = state
        if (st.frames.size >= MAX_DEPTH) throw Py.error("RecursionError", "maximum recursion depth exceeded")
        val scope = Scope(f.closure)
        scope.function = f
        bindArguments(f, scope, args, kwargs)
        st.frames.add(Frame(f, args.firstOrNull()))
        try {
            if (f.def.isGenerator) {
                val frameSelf = args.firstOrNull()
                return PyGenerator(this) { gen ->
                    val frames = state.frames
                    frames.add(Frame(f, frameSelf))
                    currentGenerator.set(gen)
                    try {
                        execBlock(f.def.body, scope)
                        PyNone
                    } catch (r: ReturnSignal) {
                        PyNone
                    } finally {
                        frames.removeAt(frames.lastIndex)
                    }
                }
            }
            f.def.lambdaBody?.let { return eval(it, scope) }
            checkInterrupt()
            return try {
                execBlock(f.def.body, scope)
                PyNone
            } catch (r: ReturnSignal) {
                r.value
            }
        } finally {
            st.frames.removeAt(st.frames.lastIndex)
        }
    }

    private fun bindArguments(f: PyFunction, scope: Scope, args: List<Any?>, kwargs: Map<String, Any?>) {
        val params = f.def.params
        val normal = params.filter { it.kind == "normal" }
        val vararg = params.firstOrNull { it.kind == "vararg" }
        val kwonly = params.filter { it.kind == "kwonly" }
        val kwarg = params.firstOrNull { it.kind == "kwarg" }
        val vars = scope.vars
        if (args.size > normal.size && vararg == null) {
            throw Py.error("TypeError", "${f.name}() takes ${normal.size} positional argument${if (normal.size == 1) "" else "s"} but ${args.size} ${if (args.size == 1) "was" else "were"} given")
        }
        for (i in 0 until minOf(args.size, normal.size)) vars[normal[i].name] = args[i]
        if (vararg != null) vars[vararg.name] = PyTuple(if (args.size > normal.size) args.subList(normal.size, args.size).toList() else emptyList())
        var extra: PyDict? = null
        for ((name, value) in kwargs) {
            val known = normal.any { it.name == name } || kwonly.any { it.name == name }
            if (known) {
                if (vars.containsKey(name)) throw Py.error("TypeError", "${f.name}() got multiple values for argument '$name'")
                vars[name] = value
            } else if (kwarg != null) {
                (extra ?: PyDict().also { extra = it }).map[name] = value
            } else {
                throw Py.error("TypeError", "${f.name}() got an unexpected keyword argument '$name'")
            }
        }
        if (kwarg != null) vars[kwarg.name] = extra ?: PyDict()
        val missing = ArrayList<String>()
        for (p in normal + kwonly) {
            if (vars.containsKey(p.name)) continue
            if (p.name in f.defaults) vars[p.name] = f.defaults[p.name] else missing += p.name
        }
        if (missing.isNotEmpty()) {
            val kind = if (kwonly.any { it.name in missing }) "keyword-only" else "positional"
            val names = missing.joinToString(" and ") { "'$it'" }
            throw Py.error("TypeError", "${f.name}() missing ${missing.size} required $kind argument${if (missing.size == 1) "" else "s"}: $names")
        }
    }

    // --- attributes -------------------------------------------------------------------------

    fun lookupAttr(obj: Any?, name: String): Any? {
        when (obj) {
            is PyInstance -> {
                obj.attrs[name]?.let { return it }
                when (name) {
                    "__class__" -> return obj.cls
                    "__dict__" -> return PyDict(LinkedHashMap<Any?, Any?>(obj.attrs))
                }
                val found = obj.cls.lookup(name)
                if (found !== Missing) return bind(found, obj, obj.cls)
                val fallback = obj.cls.lookup("__getattr__")
                if (fallback !== Missing) return call(fallback, listOf(obj, name))
                return Missing
            }
            is PyClass -> {
                when (name) {
                    "__name__" -> return obj.name
                    "__mro__" -> return PyTuple(obj.mro)
                    "__bases__" -> return PyTuple(obj.bases)
                }
                val found = obj.lookup(name)
                if (found !== Missing) return if (found is PyClassMethod) BoundMethod(obj, found.fn) else if (found is PyStaticMethod) found.fn else found
                return Builtins.classMethod(obj, name)
            }
            is PyModule -> {
                obj.attrs[name]?.let { return it }
                return when (name) {
                    "__name__" -> obj.name
                    else -> importSubmodule(obj, name)
                }
            }
            is PyObject -> return obj.attrs[name] ?: Missing
            is PySuper -> return obj.lookup(name)
            is PyFunction -> return when (name) {
                "__name__" -> obj.name
                else -> Missing
            }
            is PyBuiltin -> return if (name == "__name__") obj.name else Missing
            is PyProperty -> return when (name) {
                "setter" -> PyBuiltin("setter") { args, _ -> PyProperty(obj.getter, args[0]) }
                "getter" -> PyBuiltin("getter") { args, _ -> PyProperty(args[0], obj.setter) }
                else -> Missing
            }
            else -> return Builtins.method(obj, name)
        }
    }

    private fun bind(found: Any?, obj: Any?, cls: PyClass): Any? = when (found) {
        is PyFunction, is PyBuiltin -> BoundMethod(obj, found)
        is PyProperty -> call(found.getter, listOf(obj))
        is PyClassMethod -> BoundMethod(cls, found.fn)
        is PyStaticMethod -> found.fn
        else -> found
    }

    fun setAttr(obj: Any?, name: String, value: Any?) {
        when (obj) {
            is PyInstance -> {
                val found = obj.cls.lookup(name)
                if (found is PyProperty) {
                    call(found.setter ?: throw Py.error("AttributeError", "can't set attribute '$name'"), listOf(obj, value))
                } else {
                    obj.attrs[name] = value
                }
            }
            is PyClass -> obj.attrs[name] = value
            is PyModule -> obj.attrs[name] = value
            is PyObject -> obj.attrs[name] = value
            else -> throw Py.error("AttributeError", "'${Py.typeName(obj)}' object has no attribute '$name'")
        }
    }

    /** `super()` without arguments, inside a method. */
    fun zeroArgSuper(): PySuper {
        val frame = state.frames.lastOrNull { it.function?.owner != null } ?: throw Py.error("RuntimeError", "super(): no arguments")
        return PySuper(frame.function!!.owner!!, frame.self)
    }

    // --- imports ----------------------------------------------------------------------------

    private fun importStatement(stmt: ImportS, scope: Scope) {
        for ((name, alias) in stmt.names) {
            val module = importModule(name, scope)
            if (alias != null) {
                assign(scope, alias, module)
            } else {
                // `import a.b.c` binds the top package `a`.
                assign(scope, name.substringBefore('.'), importModule(name.substringBefore('.'), scope))
            }
        }
    }

    private fun importFrom(stmt: ImportFromS, scope: Scope) {
        val base = if (stmt.level > 0) {
            val package_ = scope.root.vars["__package__"] as? String ?: ""
            val parts = package_.split('.').filter { it.isNotEmpty() }.dropLast(stmt.level - 1)
            (parts + stmt.module.split('.').filter { it.isNotEmpty() }).joinToString(".")
        } else stmt.module
        val module = importModule(base, scope)
        for ((name, alias) in stmt.names) {
            if (name == "*") {
                module.attrs.filterKeys { !it.startsWith("_") }.forEach { (k, v) -> assign(scope, k, v) }
                continue
            }
            val value = module.attrs[name] ?: importSubmodule(module, name).takeIf { it !== Missing }
                ?: throw Py.error("ImportError", "cannot import name '$name' from '${module.name}'")
            assign(scope, alias ?: name, value)
        }
    }

    /** The module [name] ("a.b.c"), loading it and its parents the first time. */
    fun importModule(name: String, scope: Scope? = null): PyModule {
        modules[name]?.let { return it }
        val parts = name.split('.')
        if (parts.size > 1) {
            val parent = importModule(parts.dropLast(1).joinToString("."), scope)
            val child = importSubmodule(parent, parts.last())
            return child as? PyModule ?: throw Py.error("ModuleNotFoundError", "No module named '$name'")
        }
        val module = loadTop(name) ?: throw Py.error("ModuleNotFoundError", "No module named '$name'" + hint(name))
        return module
    }

    private fun hint(name: String): String = when (name) {
        "os", "subprocess", "socket", "urllib", "requests", "asyncio", "threading" ->
            ". There is no $name in the module runtime; use the mf module (mf.http, mf.storage, mf.connect…)"
        else -> ""
    }

    private fun loadTop(name: String): PyModule? {
        if (name == "mf") {
            return host.hostModule(this).also { modules[name] = it }
        }
        val packageSource = host.file("$name/__init__.py")
        val fileSource = host.file("$name.py")
        if (packageSource != null) return execModule(name, packageSource, "$name/__init__.py", name)
        if (fileSource != null) return execModule(name, fileSource, "$name.py", "")
        Stdlib.create(name, this)?.let {
            modules[name] = it
            return it
        }
        return null
    }

    private fun importSubmodule(parent: PyModule, name: String): Any? {
        val full = parent.name + "." + name
        modules[full]?.let {
            parent.attrs[name] = it
            return it
        }
        val path = full.replace('.', '/')
        val loaded = host.file("$path/__init__.py")?.let { execModule(full, it, "$path/__init__.py", full) }
            ?: host.file("$path.py")?.let { execModule(full, it, "$path.py", parent.name) }
            ?: host.bundled("$path.py")?.let { execModule(full, it, "$path.py", parent.name) }
            ?: Stdlib.create(full, this)?.also { modules[full] = it }
        if (loaded == null) return Missing
        parent.attrs[name] = loaded
        return loaded
    }

    /** Runs [source] as the module [name]; used for the libraries written in Python. */
    fun executeSource(name: String, source: String, fileName: String): PyModule = execModule(name, source, fileName, "")

    private fun execModule(name: String, source: String, fileName: String, packageName: String): PyModule {
        val scope = Scope(null)
        scope.file = fileName
        scope.vars["__name__"] = name
        scope.vars["__package__"] = packageName
        val module = PyModule(name, scope.vars)
        modules[name] = module
        val program = try {
            PyParser(source).parseModule()
        } catch (e: PySyntaxError) {
            modules.remove(name)
            throw PyError.of("SyntaxError", e.message ?: "invalid syntax").also {
                it.file = fileName
                it.line = e.line
            }
        }
        try {
            execBlock(program, scope)
        } catch (e: RuntimeException) {
            modules.remove(name)
            throw e
        }
        return module
    }

    /** Runs [action] with the generator that owns this thread's body registered, for `yield`. */
    @Suppress("unused")
    private fun bigInteger(v: BigInteger) = v
}

/** The object `super()` returns: attribute lookup that starts after [owner] in the class order of [self]. */
internal class PySuper(val owner: PyClass, val self: Any?) {
    fun lookup(name: String): Any? {
        val cls = (self as? PyInstance)?.cls ?: (self as? PyClass) ?: return Missing
        val mro = cls.mro
        val start = mro.indexOfFirst { it === owner } + 1
        for (i in start until mro.size) {
            val found = mro[i].attrs[name]
            if (found != null) {
                return when (found) {
                    is PyFunction, is PyBuiltin -> BoundMethod(self, found)
                    is PyClassMethod -> BoundMethod(cls, found.fn)
                    is PyStaticMethod -> found.fn
                    else -> found
                }
            }
        }
        return Missing
    }
}
