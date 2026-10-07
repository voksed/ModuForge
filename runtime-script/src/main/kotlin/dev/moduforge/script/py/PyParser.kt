package dev.moduforge.script.py

/** Recursive-descent parser for the subset of Python the runtime executes. */
internal class PyParser(source: String) {
    private val tokens = PyLexer(source).tokenize()
    private var i = 0
    private val yieldFlags = ArrayList<BooleanArray>()

    fun parseModule(): List<Stmt> {
        val out = ArrayList<Stmt>()
        while (peek().type != T.EOF) {
            if (peek().type == T.NEWLINE) {
                i++
                continue
            }
            out += statement()
        }
        return out
    }

    /** A single expression, as inside an f-string. */
    fun parseExpressionOnly(): Expr {
        val expr = testList(allowStar = true)
        while (peek().type == T.NEWLINE) i++
        if (peek().type != T.EOF) fail("unexpected '${peek().text}'")
        return expr
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun peek(offset: Int = 0) = tokens[minOf(i + offset, tokens.lastIndex)]

    private fun next(): Token = tokens[i].also { if (i < tokens.lastIndex) i++ }

    private fun fail(message: String, line: Int = peek().line): Nothing = throw PySyntaxError(message, line)

    private fun isOp(op: String, offset: Int = 0) = peek(offset).let { it.type == T.OP && it.text == op }

    private fun isName(word: String, offset: Int = 0) = peek(offset).let { it.type == T.NAME && it.text == word }

    private fun acceptOp(op: String): Boolean = isOp(op).also { if (it) i++ }

    private fun acceptName(word: String): Boolean = isName(word).also { if (it) i++ }

    private fun expectOp(op: String) {
        if (!acceptOp(op)) fail("expected '$op' but found '${peek().text.ifEmpty { peek().type.name }}'")
    }

    private fun expectName(word: String) {
        if (!acceptName(word)) fail("expected '$word'")
    }

    private fun identifier(): String {
        val token = next()
        if (token.type != T.NAME || token.text in KEYWORDS) fail("invalid syntax near '${token.text}'", token.line)
        return token.text
    }

    private fun <E : Expr> E.at(line: Int): E = apply { this.line = line }

    private fun <S : Stmt> S.at(line: Int): S = apply { this.line = line }

    // --- statements -------------------------------------------------------------------------

    private fun statement(): Stmt {
        val token = peek()
        if (token.type == T.INDENT) fail("unexpected indent")
        if (token.type == T.NAME) {
            when (token.text) {
                "if" -> return ifStatement()
                "while" -> return whileStatement()
                "for" -> return forStatement()
                "try" -> return tryStatement()
                "with" -> return withStatement()
                "def" -> return funcStatement(emptyList())
                "class" -> return classStatement(emptyList())
                "async" -> fail("async and await are not supported; calls wait for their result")
            }
        }
        if (isOp("@")) return decorated()
        val line = token.line
        val statements = ArrayList<Stmt>()
        statements += simpleStatement()
        while (acceptOp(";")) {
            if (peek().type == T.NEWLINE) break
            statements += simpleStatement()
        }
        if (peek().type != T.EOF && peek().type != T.DEDENT) {
            if (peek().type != T.NEWLINE) fail("invalid syntax near '${peek().text}'")
            i++
        }
        return if (statements.size == 1) statements[0] else IfS(Const(true), statements, emptyList()).at(line)
    }

    private fun block(): List<Stmt> {
        expectOp(":")
        if (peek().type != T.NEWLINE) {
            val line = peek().line
            val statements = ArrayList<Stmt>()
            statements += simpleStatement()
            while (acceptOp(";")) {
                if (peek().type == T.NEWLINE) break
                statements += simpleStatement()
            }
            if (peek().type == T.NEWLINE) i++
            return statements.also { s -> s.forEach { if (it.line == 0) it.line = line } }
        }
        i++
        if (peek().type != T.INDENT) fail("expected an indented block")
        i++
        val body = ArrayList<Stmt>()
        while (peek().type != T.DEDENT && peek().type != T.EOF) {
            if (peek().type == T.NEWLINE) {
                i++
                continue
            }
            body += statement()
        }
        if (peek().type == T.DEDENT) i++
        return body
    }

    private fun ifStatement(): Stmt {
        val line = next().line
        val cond = test()
        val body = block()
        val otherwise: List<Stmt> = when {
            isName("elif") -> listOf(ifStatement())
            acceptName("else") -> block()
            else -> emptyList()
        }
        return IfS(cond, body, otherwise).at(line)
    }

    private fun whileStatement(): Stmt {
        val line = next().line
        val cond = test()
        val body = block()
        val otherwise = if (acceptName("else")) block() else emptyList()
        return WhileS(cond, body, otherwise).at(line)
    }

    private fun forStatement(): Stmt {
        val line = next().line
        val target = targetList()
        expectName("in")
        val iter = testList(allowStar = true)
        val body = block()
        val otherwise = if (acceptName("else")) block() else emptyList()
        return ForS(target, iter, body, otherwise).at(line)
    }

    private fun tryStatement(): Stmt {
        val line = next().line
        val body = block()
        val handlers = ArrayList<Handler>()
        while (isName("except")) {
            i++
            var type: Expr? = null
            var name: String? = null
            if (!isOp(":")) {
                type = test()
                if (acceptName("as")) name = identifier()
            }
            handlers += Handler(type, name, block())
        }
        val otherwise = if (handlers.isNotEmpty() && acceptName("else")) block() else emptyList()
        val finally = if (acceptName("finally")) block() else emptyList()
        if (handlers.isEmpty() && finally.isEmpty()) fail("expected 'except' or 'finally' block")
        return TryS(body, handlers, otherwise, finally).at(line)
    }

    private fun withStatement(): Stmt {
        val line = next().line
        val items = ArrayList<Pair<Expr, Expr?>>()
        do {
            val context = test()
            val target = if (acceptName("as")) targetAtom() else null
            items += context to target
        } while (acceptOp(","))
        return WithS(items, block()).at(line)
    }

    private fun decorated(): Stmt {
        val decorators = ArrayList<Expr>()
        while (acceptOp("@")) {
            decorators += test()
            if (peek().type == T.NEWLINE) i++
        }
        return when {
            isName("def") -> funcStatement(decorators)
            isName("class") -> classStatement(decorators)
            else -> fail("a decorator must be followed by def or class")
        }
    }

    private fun funcStatement(decorators: List<Expr>): Stmt {
        val line = next().line
        val name = identifier()
        expectOp("(")
        val params = parameters(")")
        expectOp(")")
        if (acceptOp("->")) test()
        yieldFlags += BooleanArray(1)
        val body = block()
        val generator = yieldFlags.removeAt(yieldFlags.lastIndex)[0]
        return FuncS(FuncDef(name, params, body, generator), decorators).at(line)
    }

    private fun classStatement(decorators: List<Expr>): Stmt {
        val line = next().line
        val name = identifier()
        val bases = ArrayList<Expr>()
        if (acceptOp("(")) {
            while (!isOp(")")) {
                if (peek(1).let { it.type == T.OP && it.text == "=" } && peek().type == T.NAME) {
                    // A keyword such as metaclass=… is accepted and ignored.
                    i += 2
                    test()
                } else {
                    bases += test()
                }
                if (!acceptOp(",")) break
            }
            expectOp(")")
        }
        return ClassS(name, bases, block(), decorators).at(line)
    }

    /** The parameters of a def or a lambda, up to [close] (`)` or `:`). */
    private fun parameters(close: String): List<Param> {
        val params = ArrayList<Param>()
        var keywordOnly = false
        while (!isOp(close)) {
            when {
                acceptOp("/") -> Unit
                acceptOp("**") -> {
                    params += Param(identifier(), null, "kwarg")
                    if (close == ")" && acceptOp(":")) test()
                }
                acceptOp("*") -> {
                    keywordOnly = true
                    if (peek().type == T.NAME) {
                        params += Param(identifier(), null, "vararg")
                        if (close == ")" && acceptOp(":")) test()
                    }
                }
                else -> {
                    val name = identifier()
                    if (close == ")" && acceptOp(":")) test()
                    val default = if (acceptOp("=")) test() else null
                    params += Param(name, default, if (keywordOnly) "kwonly" else "normal")
                }
            }
            if (!acceptOp(",")) break
        }
        return params
    }

    private fun simpleStatement(): Stmt {
        val token = peek()
        val line = token.line
        if (token.type == T.NAME) {
            when (token.text) {
                "pass" -> { i++; return PassS().at(line) }
                "break" -> { i++; return BreakS().at(line) }
                "continue" -> { i++; return ContinueS().at(line) }
                "return" -> {
                    i++
                    val value = if (peek().type == T.NEWLINE || isOp(";") || peek().type == T.EOF) null else testList(allowStar = true)
                    return ReturnS(value).at(line)
                }
                "raise" -> {
                    i++
                    var exc: Expr? = null
                    var cause: Expr? = null
                    if (peek().type != T.NEWLINE && !isOp(";")) {
                        exc = test()
                        if (acceptName("from")) cause = test()
                    }
                    return RaiseS(exc, cause).at(line)
                }
                "global" -> { i++; return GlobalS(nameList()).at(line) }
                "nonlocal" -> { i++; return NonlocalS(nameList()).at(line) }
                "assert" -> {
                    i++
                    val cond = test()
                    return AssertS(cond, if (acceptOp(",")) test() else null).at(line)
                }
                "del" -> {
                    i++
                    val targets = ArrayList<Expr>()
                    do { targets += orExpr() } while (acceptOp(","))
                    return DelS(targets).at(line)
                }
                "import" -> return importStatement()
                "from" -> return fromStatement()
            }
        }
        return expressionStatement()
    }

    private fun nameList(): List<String> {
        val names = ArrayList<String>()
        do { names += identifier() } while (acceptOp(","))
        return names
    }

    private fun dottedName(): String {
        val sb = StringBuilder(identifier())
        while (isOp(".") && peek(1).type == T.NAME) {
            i++
            sb.append('.').append(identifier())
        }
        return sb.toString()
    }

    private fun importStatement(): Stmt {
        val line = next().line
        val names = ArrayList<Pair<String, String?>>()
        do {
            val module = dottedName()
            names += module to if (acceptName("as")) identifier() else null
        } while (acceptOp(","))
        return ImportS(names).at(line)
    }

    private fun fromStatement(): Stmt {
        val line = next().line
        var level = 0
        while (isOp(".") || isOp("...")) level += if (next().text == "...") 3 else 1
        val module = if (isName("import")) "" else dottedName()
        expectName("import")
        val names = ArrayList<Pair<String, String?>>()
        if (acceptOp("*")) {
            names += "*" to null
        } else {
            val parenthesized = acceptOp("(")
            do {
                if (parenthesized && isOp(")")) break
                val name = identifier()
                names += name to if (acceptName("as")) identifier() else null
            } while (acceptOp(","))
            if (parenthesized) expectOp(")")
        }
        return ImportFromS(module, level, names).at(line)
    }

    private fun expressionStatement(): Stmt {
        val line = peek().line
        val first = testList(allowStar = true)
        if (isOp("=")) {
            val targets = arrayListOf(first)
            var value: Expr = first
            while (acceptOp("=")) {
                value = if (isName("yield")) yieldExpression() else testList(allowStar = true)
                targets += value
            }
            targets.removeAt(targets.lastIndex)
            targets.forEach(::checkTarget)
            return AssignS(targets, value).at(line)
        }
        if (isOp(":") && first !is TupleE) {
            // A variable annotation: `x: int = 5`. The annotation is not checked.
            i++
            test()
            checkTarget(first)
            return if (acceptOp("=")) AssignS(listOf(first), testList(allowStar = true)).at(line) else PassS().at(line)
        }
        val op = peek()
        if (op.type == T.OP && op.text.length >= 2 && op.text.endsWith("=") && op.text !in COMPARISONS && op.text != ":=") {
            i++
            checkTarget(first)
            return AugAssignS(first, op.text.dropLast(1), testList(allowStar = true)).at(line)
        }
        return ExprS(first).at(line)
    }

    private fun checkTarget(target: Expr) {
        when (target) {
            is NameE, is AttrE, is SubE -> Unit
            is TupleE -> target.items.forEach(::checkTarget)
            is ListE -> target.items.forEach(::checkTarget)
            is StarE -> checkTarget(target.value)
            else -> fail("cannot assign to this expression", target.line)
        }
    }

    // --- targets of for and with --------------------------------------------------------------

    private fun targetList(): Expr {
        val line = peek().line
        val first = targetAtom()
        if (!isOp(",")) return first
        val items = arrayListOf(first)
        while (acceptOp(",")) {
            if (isName("in") || isOp("=")) break
            items += targetAtom()
        }
        return TupleE(items).at(line)
    }

    private fun targetAtom(): Expr {
        val line = peek().line
        if (acceptOp("*")) return StarE(targetAtom()).at(line)
        val e = orExpr()
        checkTarget(e)
        return e
    }

    // --- expressions ------------------------------------------------------------------------

    private fun testList(allowStar: Boolean): Expr {
        val line = peek().line
        val first = if (allowStar && isOp("*")) starred() else test()
        if (!isOp(",")) return first
        val items = arrayListOf(first)
        while (acceptOp(",")) {
            if (isExpressionEnd()) break
            items += if (allowStar && isOp("*")) starred() else test()
        }
        return TupleE(items).at(line)
    }

    private fun isExpressionEnd(): Boolean {
        val t = peek()
        return t.type == T.NEWLINE || t.type == T.EOF || (t.type == T.OP && t.text in ")]}=:;") ||
            (t.type == T.NAME && t.text in setOf("for", "in", "if", "else"))
    }

    private fun starred(): Expr {
        val line = next().line
        return StarE(orExpr()).at(line)
    }

    private fun yieldExpression(): Expr {
        val line = next().line
        yieldFlags.lastOrNull()?.set(0, true) ?: fail("'yield' outside a function")
        if (acceptName("from")) return YieldFromE(test()).at(line)
        val value = if (isExpressionEnd() || peek().type == T.NEWLINE) null else testList(allowStar = true)
        return YieldE(value).at(line)
    }

    fun test(): Expr {
        val token = peek()
        val line = token.line
        if (token.type == T.NAME) {
            if (token.text == "lambda") return lambda()
            if (token.text == "yield") return yieldExpression()
            if (peek(1).let { it.type == T.OP && it.text == ":=" } && token.text !in KEYWORDS) {
                i += 2
                return WalrusE(token.text, test()).at(line)
            }
        }
        val cond = orTest()
        if (isName("if")) {
            i++
            val test = orTest()
            expectName("else")
            val otherwise = test()
            return IfE(test, cond, otherwise).at(line)
        }
        return cond
    }

    private fun lambda(): Expr {
        val line = next().line
        val params = parameters(":")
        expectOp(":")
        yieldFlags += BooleanArray(1)
        val body = test()
        yieldFlags.removeAt(yieldFlags.lastIndex)
        return LambdaE(FuncDef("<lambda>", params, emptyList(), false).also { it.lambdaBody = body }).at(line)
    }

    private fun orTest(): Expr {
        var left = andTest()
        while (isName("or")) {
            val line = next().line
            left = BoolE(false, left, andTest()).at(line)
        }
        return left
    }

    private fun andTest(): Expr {
        var left = notTest()
        while (isName("and")) {
            val line = next().line
            left = BoolE(true, left, notTest()).at(line)
        }
        return left
    }

    private fun notTest(): Expr {
        if (isName("not")) {
            val line = next().line
            return UnaryE("not", notTest()).at(line)
        }
        return comparison()
    }

    private fun comparison(): Expr {
        val first = orExpr()
        val line = first.line
        val ops = ArrayList<Pair<String, Expr>>()
        while (true) {
            val t = peek()
            val op = when {
                t.type == T.OP && t.text in COMPARISONS -> { i++; t.text }
                t.type == T.NAME && t.text == "in" -> { i++; "in" }
                t.type == T.NAME && t.text == "not" && isName("in", 1) -> { i += 2; "not in" }
                t.type == T.NAME && t.text == "is" -> { i++; if (acceptName("not")) "is not" else "is" }
                else -> break
            }
            ops += op to orExpr()
        }
        return if (ops.isEmpty()) first else CompareE(first, ops).at(line)
    }

    private fun orExpr(): Expr = binary(0)

    private fun binary(level: Int): Expr {
        if (level >= LEVELS.size) return unary()
        var left = binary(level + 1)
        while (peek().type == T.OP && peek().text in LEVELS[level]) {
            val op = next()
            left = BinE(op.text, left, binary(level + 1)).at(op.line)
        }
        return left
    }

    private fun unary(): Expr {
        val t = peek()
        if (t.type == T.OP && (t.text == "-" || t.text == "+" || t.text == "~")) {
            i++
            return UnaryE(t.text, unary()).at(t.line)
        }
        return power()
    }

    private fun power(): Expr {
        val base = primary()
        if (isOp("**")) {
            val line = next().line
            return BinE("**", base, unary()).at(line)
        }
        return base
    }

    private fun primary(): Expr {
        var e = atom()
        while (true) {
            val t = peek()
            if (t.type != T.OP) break
            e = when (t.text) {
                "." -> {
                    i++
                    val name = next()
                    if (name.type != T.NAME) fail("invalid syntax near '${name.text}'", name.line)
                    AttrE(e, name.text).at(t.line)
                }
                "(" -> {
                    i++
                    CallE(e, arguments()).at(t.line)
                }
                "[" -> {
                    i++
                    val index = subscript()
                    expectOp("]")
                    SubE(e, index).at(t.line)
                }
                else -> break
            }
        }
        return e
    }

    private fun arguments(): List<Arg> {
        val args = ArrayList<Arg>()
        while (!isOp(")")) {
            when {
                acceptOp("**") -> args += Arg(null, test(), 2)
                acceptOp("*") -> args += Arg(null, test(), 1)
                peek().type == T.NAME && isOp("=", 1) && peek().text !in KEYWORDS -> {
                    val name = next().text
                    i++
                    args += Arg(name, test())
                }
                else -> {
                    val first = test()
                    if (isName("for")) {
                        args += Arg(null, CompE("gen", first, null, compFors()).at(first.line))
                    } else {
                        args += Arg(null, first)
                    }
                }
            }
            if (!acceptOp(",")) break
        }
        expectOp(")")
        return args
    }

    private fun subscript(): Expr {
        val line = peek().line
        val first = sliceOrTest()
        if (!isOp(",")) return first
        val items = arrayListOf(first)
        while (acceptOp(",")) {
            if (isOp("]")) break
            items += sliceOrTest()
        }
        return TupleE(items).at(line)
    }

    private fun sliceOrTest(): Expr {
        val line = peek().line
        var lo: Expr? = null
        if (!isOp(":")) {
            lo = test()
            if (!isOp(":")) return lo
        }
        expectOp(":")
        val hi = if (isOp(":") || isOp("]") || isOp(",")) null else test()
        var step: Expr? = null
        if (acceptOp(":") && !isOp("]") && !isOp(",")) step = test()
        return SliceE(lo, hi, step).at(line)
    }

    private fun compFors(): List<CompFor> {
        val fors = ArrayList<CompFor>()
        while (isName("for")) {
            i++
            val target = targetList()
            expectName("in")
            val iter = orTest()
            val conds = ArrayList<Expr>()
            while (isName("if")) {
                i++
                conds += orTest()
            }
            fors += CompFor(target, iter, conds)
        }
        return fors
    }

    private fun atom(): Expr {
        val t = next()
        val line = t.line
        when (t.type) {
            T.NUM -> return Const(t.value).at(line)
            T.STR -> {
                i--
                return strings()
            }
            T.NAME -> {
                return when (t.text) {
                    "True" -> Const(true).at(line)
                    "False" -> Const(false).at(line)
                    "None" -> Const(PyNone).at(line)
                    in KEYWORDS -> fail("invalid syntax near '${t.text}'", line)
                    else -> NameE(t.text).at(line)
                }
            }
            T.OP -> when (t.text) {
                "(" -> return parenthesized(line)
                "[" -> return listDisplay(line)
                "{" -> return braceDisplay(line)
                "..." -> return Const(PyNone).at(line)
            }
            else -> Unit
        }
        fail("invalid syntax near '${t.text.ifEmpty { t.type.name }}'", line)
    }

    private fun parenthesized(line: Int): Expr {
        if (acceptOp(")")) return TupleE(emptyList()).at(line)
        if (isName("yield")) {
            val y = yieldExpression()
            expectOp(")")
            return y
        }
        val first = if (isOp("*")) starred() else test()
        if (isName("for")) {
            val comp = CompE("gen", first, null, compFors()).at(line)
            expectOp(")")
            return comp
        }
        if (isOp(",")) {
            val items = arrayListOf(first)
            while (acceptOp(",")) {
                if (isOp(")")) break
                items += if (isOp("*")) starred() else test()
            }
            expectOp(")")
            return TupleE(items).at(line)
        }
        expectOp(")")
        return first
    }

    private fun listDisplay(line: Int): Expr {
        if (acceptOp("]")) return ListE(emptyList()).at(line)
        val first = if (isOp("*")) starred() else test()
        if (isName("for")) {
            val comp = CompE("list", first, null, compFors()).at(line)
            expectOp("]")
            return comp
        }
        val items = arrayListOf(first)
        while (acceptOp(",")) {
            if (isOp("]")) break
            items += if (isOp("*")) starred() else test()
        }
        expectOp("]")
        return ListE(items).at(line)
    }

    private fun braceDisplay(line: Int): Expr {
        if (acceptOp("}")) return DictE(emptyList()).at(line)
        if (acceptOp("**")) {
            val entries = arrayListOf<Pair<Expr?, Expr>>(null to orExpr())
            return dictRest(entries, line)
        }
        val first = if (isOp("*")) starred() else test()
        if (acceptOp(":")) {
            val value = test()
            if (isName("for")) {
                val comp = CompE("dict", first, value, compFors()).at(line)
                expectOp("}")
                return comp
            }
            return dictRest(arrayListOf(first to value), line)
        }
        if (isName("for")) {
            val comp = CompE("set", first, null, compFors()).at(line)
            expectOp("}")
            return comp
        }
        val items = arrayListOf(first)
        while (acceptOp(",")) {
            if (isOp("}")) break
            items += if (isOp("*")) starred() else test()
        }
        expectOp("}")
        return SetE(items).at(line)
    }

    private fun dictRest(entries: ArrayList<Pair<Expr?, Expr>>, line: Int): Expr {
        while (acceptOp(",")) {
            if (isOp("}")) break
            if (acceptOp("**")) {
                entries += null to orExpr()
            } else {
                val key = test()
                expectOp(":")
                entries += key to test()
            }
        }
        expectOp("}")
        return DictE(entries).at(line)
    }

    // --- strings ----------------------------------------------------------------------------

    private fun strings(): Expr {
        val line = peek().line
        val parts = ArrayList<FPart>()
        var anyF = false
        var bytes = false
        while (peek().type == T.STR) {
            val t = next()
            val raw = 'r' in t.flags
            if ('b' in t.flags) bytes = true
            if ('f' in t.flags) {
                anyF = true
                parts += fstringParts(t.text, raw, t.line)
            } else {
                parts += FPart(unescape(t.text, raw, t.line), null, null, null)
            }
        }
        if (!anyF) {
            val text = parts.joinToString("") { it.text.orEmpty() }
            return Const(if (bytes) PyBytes(text.toByteArray(Charsets.ISO_8859_1)) else text).at(line)
        }
        return FStringE(parts).at(line)
    }

    /** Splits the body of an f-string into literal text and replacement fields. */
    private fun fstringParts(body: String, raw: Boolean, line: Int): List<FPart> {
        val parts = ArrayList<FPart>()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                parts += FPart(unescape(literal.toString(), raw, line), null, null, null)
                literal.clear()
            }
        }
        var p = 0
        while (p < body.length) {
            val c = body[p]
            when {
                c == '{' && p + 1 < body.length && body[p + 1] == '{' -> { literal.append('{'); p += 2 }
                c == '}' && p + 1 < body.length && body[p + 1] == '}' -> { literal.append('}'); p += 2 }
                c == '}' -> fail("single '}' is not allowed in an f-string", line)
                c == '{' -> {
                    flush()
                    val end = fieldEnd(body, p + 1, line)
                    parts += field(body.substring(p + 1, end), raw, line)
                    p = end + 1
                }
                else -> { literal.append(c); p++ }
            }
        }
        flush()
        return parts
    }

    /** Index of the `}` closing the replacement field that starts at [from]. */
    private fun fieldEnd(body: String, from: Int, line: Int): Int {
        var depth = 0
        var p = from
        var quote: Char? = null
        while (p < body.length) {
            val c = body[p]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']' -> depth--
                '}' -> if (depth == 0) return p else depth--
            }
            p++
        }
        fail("f-string: expecting '}'", line)
    }

    private fun field(text: String, raw: Boolean, line: Int): FPart {
        // Find the top-level `!` or `:` that ends the expression.
        var depth = 0
        var quote: Char? = null
        var end = text.length
        var p = 0
        while (p < text.length) {
            val c = text[p]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '!' -> if (depth == 0 && p + 1 < text.length && text[p + 1] != '=') { end = p; break }
                ':' -> if (depth == 0) { end = p; break }
            }
            p++
        }
        var expressionText = text.substring(0, end)
        var conversion: Char? = null
        var spec: List<FPart>? = null
        var rest = text.substring(end)
        if (rest.startsWith("!")) {
            conversion = rest.getOrNull(1)
            rest = rest.drop(2)
        }
        if (rest.startsWith(":")) spec = fstringParts(rest.drop(1), raw, line)
        var debug: String? = null
        if (expressionText.trimEnd().endsWith("=") && !expressionText.trimEnd().endsWith("==")) {
            debug = expressionText
            expressionText = expressionText.trimEnd().dropLast(1)
            if (conversion == null && spec == null) conversion = 'r'
        }
        val expr = try {
            PyParser(expressionText.trim()).parseExpressionOnly()
        } catch (e: PySyntaxError) {
            fail("f-string: ${e.message}", line)
        }
        expr.line = line
        val shown = if (debug != null) FPart(debug, null, null, null) else null
        return if (shown == null) FPart(null, expr, conversion, spec) else FPart(null, FStringE(listOf(shown, FPart(null, expr, conversion, spec))), null, null)
    }

    companion object {
        private val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=", "<>")
        private val LEVELS = listOf(
            setOf("|"), setOf("^"), setOf("&"), setOf("<<", ">>"), setOf("+", "-"), setOf("*", "/", "//", "%", "@"),
        )
        val KEYWORDS = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or",
            "pass", "raise", "return", "try", "while", "with", "yield",
        )

        /** Decodes the escape sequences of a string literal body. */
        fun unescape(body: String, raw: Boolean, line: Int): String {
            if (raw || '\\' !in body) return body
            val sb = StringBuilder()
            var p = 0
            while (p < body.length) {
                val c = body[p]
                if (c != '\\' || p + 1 >= body.length) {
                    sb.append(c)
                    p++
                    continue
                }
                val n = body[p + 1]
                p += 2
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '0' -> sb.append('\u0000')
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'v' -> sb.append('\u000b')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '\n' -> Unit
                    'x' -> { sb.append(hex(body, p, 2, line).toChar()); p += 2 }
                    'u' -> { sb.append(hex(body, p, 4, line).toChar()); p += 4 }
                    'U' -> { sb.appendCodePoint(hex(body, p, 8, line)); p += 8 }
                    else -> sb.append('\\').append(n)
                }
            }
            return sb.toString()
        }

        private fun hex(body: String, from: Int, count: Int, line: Int): Int =
            body.substring(from, minOf(from + count, body.length)).toIntOrNull(16) ?: throw PySyntaxError("invalid escape sequence", line)
    }
}
