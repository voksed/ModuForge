package dev.moduforge.script.py

import java.math.BigInteger

/** A syntax error in a script, with the line it was found on. */
internal class PySyntaxError(message: String, val line: Int) : Exception(message)

internal enum class T { NAME, NUM, STR, OP, NEWLINE, INDENT, DEDENT, EOF }

/**
 * @property text name, operator, or the raw text between the quotes of a string.
 * @property value the number of a [T.NUM] token.
 * @property flags for a string: its prefix letters in lower case (`f`, `r`, `b`).
 */
internal class Token(val type: T, val text: String, val line: Int, val value: Any? = null, val flags: String = "") {
    override fun toString() = "$type($text)@$line"
}

/** Turns Python source into tokens, with INDENT and DEDENT tokens for the block structure. */
internal class PyLexer(private val source: String) {
    private val out = ArrayList<Token>()
    private var pos = 0
    private var line = 1
    private var depth = 0
    private val indents = ArrayList<Int>().apply { add(0) }
    private var atLineStart = true

    fun tokenize(): List<Token> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        val s = text
        while (pos < s.length) {
            if (atLineStart && depth == 0) {
                if (!handleIndent(s)) continue
            }
            val c = s[pos]
            when {
                c == '\n' -> {
                    if (depth == 0 && out.isNotEmpty() && out.last().type != T.NEWLINE && out.last().type != T.INDENT && out.last().type != T.DEDENT) {
                        out += Token(T.NEWLINE, "\n", line)
                    }
                    line++
                    pos++
                    atLineStart = depth == 0
                }
                c == ' ' || c == '\t' || c == '\u000c' -> pos++
                c == '#' -> while (pos < s.length && s[pos] != '\n') pos++
                c == '\\' && pos + 1 < s.length && s[pos + 1] == '\n' -> {
                    pos += 2
                    line++
                }
                c.isLetter() || c == '_' -> name(s)
                c.isDigit() || (c == '.' && pos + 1 < s.length && s[pos + 1].isDigit()) -> number(s)
                c == '"' || c == '\'' -> string(s, "")
                else -> operator(s)
            }
        }
        if (out.isNotEmpty() && out.last().type != T.NEWLINE) out += Token(T.NEWLINE, "\n", line)
        while (indents.size > 1) {
            indents.removeAt(indents.lastIndex)
            out += Token(T.DEDENT, "", line)
        }
        out += Token(T.EOF, "", line)
        return out
    }

    /** Reads the indentation of a new line. @return false when the line is blank or a comment and was skipped. */
    private fun handleIndent(s: String): Boolean {
        var width = 0
        var p = pos
        while (p < s.length) {
            when (s[p]) {
                ' ' -> width++
                '\t' -> width = (width / 8 + 1) * 8
                '\u000c' -> width = 0
                else -> break
            }
            p++
        }
        if (p >= s.length) {
            pos = p
            return true
        }
        if (s[p] == '\n' || s[p] == '#') {
            // A blank or comment-only line carries no indentation.
            while (p < s.length && s[p] != '\n') p++
            pos = p
            if (pos < s.length) {
                pos++
                line++
            }
            return false
        }
        pos = p
        atLineStart = false
        if (width > indents.last()) {
            indents += width
            out += Token(T.INDENT, "", line)
        } else {
            while (width < indents.last()) {
                indents.removeAt(indents.lastIndex)
                out += Token(T.DEDENT, "", line)
            }
            if (width != indents.last()) throw PySyntaxError("unindent does not match any outer indentation level", line)
        }
        return true
    }

    private fun name(s: String) {
        val start = pos
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
        val word = s.substring(start, pos)
        // A string prefix: r"", b"", f"", rb"", fr"" and so on.
        if (pos < s.length && (s[pos] == '"' || s[pos] == '\'') && word.length <= 2 && word.lowercase().all { it in "rbfu" }) {
            string(s, word.lowercase())
            return
        }
        out += Token(T.NAME, word, line)
    }

    private fun number(s: String) {
        val start = pos
        if (s[pos] == '0' && pos + 1 < s.length && s[pos + 1] in "xXoObB") {
            val radix = when (s[pos + 1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
            pos += 2
            val digits = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
            val big = BigInteger(s.substring(digits, pos).replace("_", ""), radix)
            out += Token(T.NUM, s.substring(start, pos), line, normalize(big))
            return
        }
        var isFloat = false
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '_')) pos++
        if (pos < s.length && s[pos] == '.') {
            isFloat = true
            pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '_')) pos++
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            var p = pos + 1
            if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
            if (p < s.length && s[p].isDigit()) {
                isFloat = true
                pos = p
                while (pos < s.length && s[pos].isDigit()) pos++
            }
        }
        val text = s.substring(start, pos).replace("_", "")
        val value: Any = if (isFloat) text.toDouble() else normalize(BigInteger(text))
        out += Token(T.NUM, text, line, value)
    }

    private fun string(s: String, prefix: String) {
        val quote = s[pos]
        val triple = pos + 2 < s.length && s[pos + 1] == quote && s[pos + 2] == quote
        val startLine = line
        pos += if (triple) 3 else 1
        val body = StringBuilder()
        val raw = 'r' in prefix
        while (true) {
            if (pos >= s.length) throw PySyntaxError("unterminated string literal", startLine)
            val c = s[pos]
            if (c == '\\' && pos + 1 < s.length) {
                // The escape is decoded later; here it only has to not end the string.
                body.append(c).append(s[pos + 1])
                if (s[pos + 1] == '\n') line++
                pos += 2
                continue
            }
            if (triple) {
                if (c == quote && pos + 2 < s.length + 0 && s.startsWith("$quote$quote$quote", pos)) {
                    pos += 3
                    break
                }
            } else {
                if (c == quote) {
                    pos++
                    break
                }
                if (c == '\n') throw PySyntaxError("unterminated string literal", startLine)
            }
            if (c == '\n') line++
            body.append(c)
            pos++
        }
        out += Token(T.STR, body.toString(), startLine, flags = prefix.replace("u", "").let { if (raw) it else it })
    }

    private fun operator(s: String) {
        val three = s.substring(pos, minOf(pos + 3, s.length))
        val two = s.substring(pos, minOf(pos + 2, s.length))
        val op = when {
            three in THREE -> three
            two in TWO -> two
            s[pos] in ONE -> s[pos].toString()
            else -> throw PySyntaxError("invalid character '${s[pos]}'", line)
        }
        pos += op.length
        when (op) {
            "(", "[", "{" -> depth++
            ")", "]", "}" -> if (depth > 0) depth--
        }
        out += Token(T.OP, op, line)
    }

    companion object {
        private val THREE = setOf("**=", "//=", ">>=", "<<=", "...")
        private val TWO = setOf("==", "!=", "<=", ">=", "**", "//", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "->", ":=", "<<", ">>", "@=")
        private const val ONE = "+-*/%@&|^~<>()[]{},:.;=!"

        fun normalize(value: BigInteger): Any = if (value.bitLength() < 64) value.toLong() else value
    }
}
