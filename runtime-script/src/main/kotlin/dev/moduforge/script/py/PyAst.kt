package dev.moduforge.script.py

internal sealed class Expr {
    var line: Int = 0
}

internal class Const(val value: Any?) : Expr()
internal class NameE(val id: String) : Expr()
internal class AttrE(val obj: Expr, val name: String) : Expr()
internal class SubE(val obj: Expr, val index: Expr) : Expr()
internal class SliceE(val lo: Expr?, val hi: Expr?, val step: Expr?) : Expr()

/** @property star 0 for a plain argument, 1 for `*items`, 2 for `**mapping`; [name] is set for `name=value`. */
internal class Arg(val name: String?, val value: Expr, val star: Int = 0)
internal class CallE(val fn: Expr, val args: List<Arg>) : Expr()
internal class BinE(val op: String, val left: Expr, val right: Expr) : Expr()
internal class UnaryE(val op: String, val operand: Expr) : Expr()
internal class BoolE(val isAnd: Boolean, val left: Expr, val right: Expr) : Expr()
internal class CompareE(val first: Expr, val ops: List<Pair<String, Expr>>) : Expr()
internal class IfE(val cond: Expr, val then: Expr, val otherwise: Expr) : Expr()
internal class LambdaE(val def: FuncDef) : Expr()
internal class ListE(val items: List<Expr>) : Expr()
internal class TupleE(val items: List<Expr>) : Expr()
internal class SetE(val items: List<Expr>) : Expr()

/** Entries of a dict display; a null key means `**value`. */
internal class DictE(val entries: List<Pair<Expr?, Expr>>) : Expr()
internal class StarE(val value: Expr) : Expr()
internal class YieldE(val value: Expr?) : Expr()
internal class YieldFromE(val value: Expr) : Expr()
internal class WalrusE(val name: String, val value: Expr) : Expr()

/** A piece of an f-string: literal text, or an expression with its conversion and format spec. */
internal class FPart(val text: String?, val expr: Expr?, val conversion: Char?, val spec: List<FPart>?)
internal class FStringE(val parts: List<FPart>) : Expr()

/** One `for target in iter if cond…` of a comprehension. */
internal class CompFor(val target: Expr, val iter: Expr, val conds: List<Expr>)

/** @property kind `list`, `set`, `gen` or `dict` (then [value] is the value and [element] the key). */
internal class CompE(val kind: String, val element: Expr, val value: Expr?, val fors: List<CompFor>) : Expr()

internal sealed class Stmt {
    var line: Int = 0
}

internal class ExprS(val expr: Expr) : Stmt()
internal class AssignS(val targets: List<Expr>, val value: Expr) : Stmt()
internal class AugAssignS(val target: Expr, val op: String, val value: Expr) : Stmt()
internal class IfS(val cond: Expr, val body: List<Stmt>, val otherwise: List<Stmt>) : Stmt()
internal class WhileS(val cond: Expr, val body: List<Stmt>, val otherwise: List<Stmt>) : Stmt()
internal class ForS(val target: Expr, val iter: Expr, val body: List<Stmt>, val otherwise: List<Stmt>) : Stmt()
internal class Handler(val type: Expr?, val name: String?, val body: List<Stmt>)
internal class TryS(val body: List<Stmt>, val handlers: List<Handler>, val otherwise: List<Stmt>, val finally: List<Stmt>) : Stmt()
internal class WithS(val items: List<Pair<Expr, Expr?>>, val body: List<Stmt>) : Stmt()

/** @property kind `normal`, `vararg`, `kwonly` or `kwarg`. */
internal class Param(val name: String, val default: Expr?, val kind: String)
internal class FuncDef(val name: String, val params: List<Param>, val body: List<Stmt>, val isGenerator: Boolean) {
    var lambdaBody: Expr? = null
}
internal class FuncS(val def: FuncDef, val decorators: List<Expr>) : Stmt()
internal class ClassS(val name: String, val bases: List<Expr>, val body: List<Stmt>, val decorators: List<Expr>) : Stmt()
internal class ReturnS(val value: Expr?) : Stmt()
internal class PassS : Stmt()
internal class BreakS : Stmt()
internal class ContinueS : Stmt()
internal class RaiseS(val exc: Expr?, val cause: Expr?) : Stmt()
internal class AssertS(val cond: Expr, val message: Expr?) : Stmt()
internal class DelS(val targets: List<Expr>) : Stmt()
internal class GlobalS(val names: List<String>) : Stmt()
internal class NonlocalS(val names: List<String>) : Stmt()

/** `import a.b as c`: [alias] is null when there is none. */
internal class ImportS(val names: List<Pair<String, String?>>) : Stmt()

/** `from . import x` has [level] 1; a `*` import has the single name `*`. */
internal class ImportFromS(val module: String, val level: Int, val names: List<Pair<String, String?>>) : Stmt()
