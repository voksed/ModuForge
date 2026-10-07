package dev.moduforge.script.py

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** `%` formatting, `str.format` and the format-spec mini language. */
internal object Formatting {

    private val SPEC = Regex("""^(?:(.)?([<>=^]))?([+\- ])?(#)?(0)?(\d+)?([,_])?(?:\.(\d+))?([a-zA-Z%])?$""", RegexOption.DOT_MATCHES_ALL)

    /** @param spec the text after the colon in `{value:spec}` or in `format(value, spec)`. */
    fun format(value: Any?, spec: String): String {
        if (value is PyInstance) {
            val custom = Interpreter.current().callSpecial(value, "__format__", listOf(spec))
            if (custom !== Missing) return custom as? String ?: throw Py.error("TypeError", "__format__ must return a str")
        }
        if (spec.isEmpty()) return Py.str(value)
        val m = SPEC.matchEntire(spec) ?: throw Py.error("ValueError", "Invalid format specifier '$spec'")
        var fill = m.groupValues[1].ifEmpty { " " }
        var align = m.groupValues[2]
        val sign = m.groupValues[3]
        val alternate = m.groupValues[4].isNotEmpty()
        val zero = m.groupValues[5].isNotEmpty()
        val width = m.groupValues[6].toIntOrNull() ?: 0
        val grouping = m.groupValues[7]
        val precision = m.groupValues[8].toIntOrNull()
        var type = m.groupValues[9]

        val numeric = Py.isNumber(value)
        if (zero && align.isEmpty()) {
            fill = "0"
            align = "="
        }
        var body: String
        var negative = false
        if (numeric) {
            if (value is Double || type in setOf("e", "E", "f", "F", "g", "G", "%")) {
                if (type.isEmpty()) type = if (precision != null) "g" else ""
                val d = Py.toDouble(value)
                negative = d < 0 || (d == 0.0 && 1.0 / d < 0)
                val a = Math.abs(d)
                body = when {
                    d.isNaN() -> "nan"
                    d.isInfinite() -> "inf"
                    type == "" -> Py.floatRepr(a)
                    type == "f" || type == "F" -> BigDecimal(a).setScale(precision ?: 6, RoundingMode.HALF_EVEN).toPlainString()
                    type == "%" -> BigDecimal(a * 100).setScale(precision ?: 6, RoundingMode.HALF_EVEN).toPlainString() + "%"
                    type == "e" || type == "E" -> exponent(a, precision ?: 6, type == "E")
                    else -> general(a, precision ?: 6, type == "G", alternate)
                }
            } else {
                val n = Py.toBig(value)
                negative = n.signum() < 0
                val a = n.abs()
                if (precision != null) throw Py.error("ValueError", "Precision not allowed in integer format specifier")
                body = when (type) {
                    "", "d", "n" -> a.toString()
                    "x" -> (if (alternate) "0x" else "") + a.toString(16)
                    "X" -> (if (alternate) "0X" else "") + a.toString(16).uppercase()
                    "o" -> (if (alternate) "0o" else "") + a.toString(8)
                    "b" -> (if (alternate) "0b" else "") + a.toString(2)
                    "c" -> String(Character.toChars(a.toInt()))
                    else -> throw Py.error("ValueError", "Unknown format code '$type' for object of type 'int'")
                }
            }
            if (grouping.isNotEmpty() && body.firstOrNull()?.isDigit() == true) body = group(body, grouping)
        } else {
            if (type != "" && type != "s") throw Py.error("ValueError", "Unknown format code '$type' for object of type '${Py.typeName(value)}'")
            body = Py.str(value)
            if (precision != null) body = body.take(precision)
            if (align == "=") throw Py.error("ValueError", "'=' alignment not allowed in string format specifier")
        }
        val signText = if (numeric) (if (negative) "-" else if (sign == "+") "+" else if (sign == " ") " " else "") else ""
        val padding = (width - signText.length - body.length).coerceAtLeast(0)
        val effective = align.ifEmpty { if (numeric) ">" else "<" }
        return when (effective) {
            "<" -> signText + body + fill.repeat(padding)
            ">" -> fill.repeat(padding) + signText + body
            "^" -> fill.repeat(padding / 2) + signText + body + fill.repeat(padding - padding / 2)
            else -> signText + fill.repeat(padding) + body
        }
    }

    private fun group(digits: String, separator: String): String {
        val point = digits.indexOf('.').let { if (it < 0) digits.length else it }
        val whole = digits.substring(0, point)
        val rest = digits.substring(point)
        val sb = StringBuilder()
        whole.forEachIndexed { i, c ->
            if (i > 0 && (whole.length - i) % 3 == 0) sb.append(separator)
            sb.append(c)
        }
        return sb.toString() + rest
    }

    private fun exponent(a: Double, precision: Int, upper: Boolean): String {
        if (a == 0.0) return "0" + (if (precision > 0) "." + "0".repeat(precision) else "") + (if (upper) "E+00" else "e+00")
        val rounded = BigDecimal(a).round(MathContext(precision + 1, RoundingMode.HALF_EVEN))
        val exp = rounded.precision() - rounded.scale() - 1
        val digits = rounded.unscaledValue().toString().padEnd(precision + 1, '0').take(precision + 1)
        val mantissa = if (precision > 0) digits[0] + "." + digits.substring(1) else digits
        val text = mantissa + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        return if (upper) text.uppercase() else text
    }

    private fun general(a: Double, precision: Int, upper: Boolean, alternate: Boolean): String {
        val p = if (precision == 0) 1 else precision
        if (a == 0.0) return "0"
        val rounded = BigDecimal(a).round(MathContext(p, RoundingMode.HALF_EVEN))
        val exp = rounded.precision() - rounded.scale() - 1
        val text = if (exp < -4 || exp >= p) {
            exponent(a, p - 1, upper).let { if (alternate) it else stripExponentZeros(it) }
        } else {
            BigDecimal(a).setScale((p - 1 - exp).coerceAtLeast(0), RoundingMode.HALF_EVEN).toPlainString().let {
                if (alternate || '.' !in it) it else it.trimEnd('0').trimEnd('.')
            }
        }
        return text
    }

    private fun stripExponentZeros(text: String): String {
        val e = text.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = text.substring(0, e)
        return (if ('.' in mantissa) mantissa.trimEnd('0').trimEnd('.') else mantissa) + text.substring(e)
    }

    // --- % operator ----------------------------------------------------------------------------

    fun percent(format: String, arguments: Any?): String {
        val values: List<Any?> = if (arguments is PyTuple) arguments.items else listOf(arguments)
        val mapping = arguments as? PyDict
        var next = 0
        fun take(): Any? {
            if (next >= values.size) throw Py.error("TypeError", "not enough arguments for format string")
            return values[next++]
        }
        val sb = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c != '%') {
                sb.append(c)
                i++
                continue
            }
            i++
            if (i >= format.length) throw Py.error("ValueError", "incomplete format")
            var key: String? = null
            if (format[i] == '(') {
                val close = format.indexOf(')', i)
                if (close < 0) throw Py.error("ValueError", "incomplete format key")
                key = format.substring(i + 1, close)
                i = close + 1
            }
            var flags = ""
            while (i < format.length && format[i] in "-+ 0#") flags += format[i++]
            var width = ""
            if (i < format.length && format[i] == '*') { width = Py.toLong(take()).toString(); i++ } else while (i < format.length && format[i].isDigit()) width += format[i++]
            var precision: String? = null
            if (i < format.length && format[i] == '.') {
                i++
                precision = ""
                if (i < format.length && format[i] == '*') { precision = Py.toLong(take()).toString(); i++ } else while (i < format.length && format[i].isDigit()) precision += format[i++]
            }
            while (i < format.length && format[i] in "hlL") i++
            if (i >= format.length) throw Py.error("ValueError", "incomplete format")
            val type = format[i++]
            if (type == '%') {
                sb.append('%')
                continue
            }
            val value = if (key != null) {
                (mapping ?: throw Py.error("TypeError", "format requires a mapping")).map[key] ?: throw PyError.of("KeyError", Py.stringRepr(key))
            } else take()
            var left = '-' in flags
            var w = width.toIntOrNull() ?: 0
            if (w < 0) { left = true; w = -w }
            val p = precision?.toIntOrNull() ?: if (precision != null) 0 else null
            val text: String = when (type) {
                's' -> Py.str(value).let { if (p != null) it.take(p) else it }
                'r', 'a' -> Py.repr(value).let { if (p != null) it.take(p) else it }
                'c' -> if (value is String) value else String(Character.toChars(Py.toInt(value)))
                'd', 'i', 'u' -> signed(Py.toBig(if (value is Double) Py.norm(BigDecimal(value).toBigInteger()) else value).let { it.abs().toString() }, Py.toBig(if (value is Double) Py.norm(BigDecimal(value).toBigInteger()) else value).signum() < 0, flags)
                'x' -> signed((if ('#' in flags) "0x" else "") + Py.toBig(value).abs().toString(16), Py.toBig(value).signum() < 0, flags)
                'X' -> signed((if ('#' in flags) "0X" else "") + Py.toBig(value).abs().toString(16).uppercase(), Py.toBig(value).signum() < 0, flags)
                'o' -> signed((if ('#' in flags) "0o" else "") + Py.toBig(value).abs().toString(8), Py.toBig(value).signum() < 0, flags)
                'f', 'F', 'e', 'E', 'g', 'G' -> {
                    val d = Py.toDouble(value)
                    val spec = (if ('+' in flags) "+" else if (' ' in flags) " " else "") + (if ('#' in flags) "#" else "") + "." + (p ?: 6) + type
                    format(d, spec)
                }
                else -> throw Py.error("ValueError", "unsupported format character '$type'")
            }
            val zeroPad = '0' in flags && !left && type !in "sra"
            val pad = (w - text.length).coerceAtLeast(0)
            sb.append(
                when {
                    left -> text + " ".repeat(pad)
                    zeroPad -> {
                        val signLength = if (text.startsWith("-") || text.startsWith("+") || text.startsWith(" ")) 1 else 0
                        text.substring(0, signLength) + "0".repeat(pad) + text.substring(signLength)
                    }
                    else -> " ".repeat(pad) + text
                },
            )
        }
        if (mapping == null && arguments !is PyTuple && next < values.size && next == 0 && values.size == 1 && !formatConsumed(format)) {
            throw Py.error("TypeError", "not all arguments converted during string formatting")
        }
        if (arguments is PyTuple && next < values.size) throw Py.error("TypeError", "not all arguments converted during string formatting")
        return sb.toString()
    }

    private fun formatConsumed(format: String) = Regex("%[^%]").containsMatchIn(format.replace("%%", ""))

    private fun signed(digits: String, negative: Boolean, flags: String): String =
        (if (negative) "-" else if ('+' in flags) "+" else if (' ' in flags) " " else "") + digits

    // --- str.format ----------------------------------------------------------------------------

    fun strFormat(template: String, args: List<Any?>, kwargs: Map<String, Any?>): String {
        val sb = StringBuilder()
        var auto = 0
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> { sb.append('{'); i += 2 }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> { sb.append('}'); i += 2 }
                c == '}' -> throw Py.error("ValueError", "Single '}' encountered in format string")
                c == '{' -> {
                    var depth = 1
                    var j = i + 1
                    while (j < template.length && depth > 0) {
                        if (template[j] == '{') depth++ else if (template[j] == '}') depth--
                        j++
                    }
                    if (depth != 0) throw Py.error("ValueError", "expected '}' before end of string")
                    var field = template.substring(i + 1, j - 1)
                    var spec = ""
                    var conversion: Char? = null
                    val colon = topLevel(field, ':')
                    if (colon >= 0) {
                        spec = field.substring(colon + 1)
                        field = field.substring(0, colon)
                    }
                    val bang = field.indexOf('!')
                    if (bang >= 0) {
                        conversion = field.getOrNull(bang + 1)
                        field = field.substring(0, bang)
                    }
                    if ('{' in spec) spec = strFormat(spec, args, kwargs)
                    val head = field.takeWhile { it != '.' && it != '[' }
                    var value: Any? = when {
                        head.isEmpty() -> args.getOrNull(auto++) ?: throw Py.error("IndexError", "Replacement index ${auto - 1} out of range for positional args tuple")
                        head.all { it.isDigit() } -> args.getOrNull(head.toInt()) ?: throw Py.error("IndexError", "Replacement index $head out of range for positional args tuple")
                        else -> if (head in kwargs) kwargs[head] else throw PyError.of("KeyError", Py.stringRepr(head))
                    }
                    var rest = field.substring(head.length)
                    while (rest.isNotEmpty()) {
                        if (rest[0] == '.') {
                            val name = rest.drop(1).takeWhile { it != '.' && it != '[' }
                            value = Py.getAttr(value, name)
                            rest = rest.substring(1 + name.length)
                        } else {
                            val close = rest.indexOf(']')
                            val key = rest.substring(1, close)
                            value = Py.getItem(value, key.toLongOrNull() ?: key)
                            rest = rest.substring(close + 1)
                        }
                    }
                    value = when (conversion) {
                        'r', 'a' -> Py.repr(value)
                        's' -> Py.str(value)
                        else -> value
                    }
                    sb.append(format(value, spec))
                    i = j
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun topLevel(text: String, target: Char): Int {
        var depth = 0
        text.forEachIndexed { index, c ->
            when {
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
                c == target && depth == 0 -> return index
            }
        }
        return -1
    }

    @Suppress("unused")
    private fun unusedBig(v: BigInteger) = v
}
