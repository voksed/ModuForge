package dev.moduforge.host.ui.editor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moduforge.sdk.ModuleRuntimeKind

/** Token colours of the code editor, one set for dark and one for light surfaces. */
internal class SyntaxPalette(val keyword: Color, val string: Color, val number: Color, val api: Color) {
    companion object {
        val DARK = SyntaxPalette(Color(0xFFC792EA), Color(0xFFA5D6A7), Color(0xFFFFAB70), Color(0xFF82B1FF))
        val LIGHT = SyntaxPalette(Color(0xFF7B1FA2), Color(0xFF2E7D32), Color(0xFFC2410C), Color(0xFF1565C0))
    }
}

/** Colours and token rules for one language. */
internal class CodeHighlighter(private val runtime: ModuleRuntimeKind, private val colors: ColorScheme) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    // Fixed hues: a monochrome or wallpaper theme would otherwise leave every token the same colour.
    private val palette = if (colors.surface.luminance() < 0.5f) SyntaxPalette.DARK else SyntaxPalette.LIGHT

    fun highlight(code: String): AnnotatedString {
        val builder = AnnotatedString.Builder(code)
        val pattern = if (runtime == ModuleRuntimeKind.JS) JS_TOKENS else LUA_TOKENS
        val keywords = if (runtime == ModuleRuntimeKind.JS) JS_KEYWORDS else LUA_KEYWORDS
        for (match in pattern.findAll(code)) {
            val token = match.value
            val color = when {
                match.groups[COMMENT] != null -> colors.outline
                match.groups[STRING] != null -> palette.string
                match.groups[NUMBER] != null -> palette.number
                token == "mf" -> palette.api
                token in keywords -> palette.keyword
                else -> continue
            }
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return builder.toAnnotatedString()
    }

    private companion object {
        // Group numbers shared by both token patterns.
        const val COMMENT = 1
        const val STRING = 2
        const val NUMBER = 3

        val LUA_TOKENS = Regex(
            """(--\[\[[\s\S]*?(?:]]|$)|--[^\n]*)|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?|\[\[[\s\S]*?(?:]]|$))""" +
                """|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_]\w*\b)""",
        )
        val JS_TOKENS = Regex(
            """(/\*[\s\S]*?(?:\*/|$)|//[^\n]*)|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?|`(?:\\.|[^`\\])*`?)""" +
                """|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_$][\w$]*\b)""",
        )
        val LUA_KEYWORDS = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local",
            "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
        )
        val JS_KEYWORDS = setOf(
            "break", "case", "catch", "const", "continue", "default", "delete", "do", "else", "false", "finally", "for",
            "function", "if", "in", "instanceof", "let", "new", "null", "of", "return", "switch", "this", "throw", "true",
            "try", "typeof", "undefined", "var", "void", "while",
        )
    }
}

/**
 * Applies an edit on top of [previous], continuing the indentation of the current line when
 * the edit is a single line break typed at the cursor.
 */
internal fun withAutoIndent(previous: TextFieldValue, edited: TextFieldValue): TextFieldValue {
    val cursor = edited.selection.start
    val typedLineBreak = edited.selection.collapsed &&
        edited.text.length == previous.text.length + 1 &&
        cursor > 0 && edited.text[cursor - 1] == '\n' &&
        previous.selection.collapsed && previous.selection.start == cursor - 1
    if (!typedLineBreak) return edited
    val lineStart = edited.text.lastIndexOf('\n', cursor - 2) + 1
    val indent = edited.text.substring(lineStart, cursor - 1).takeWhile { it == ' ' || it == '\t' }
    if (indent.isEmpty()) return edited
    return TextFieldValue(
        text = edited.text.substring(0, cursor) + indent + edited.text.substring(cursor),
        selection = TextRange(cursor + indent.length),
    )
}

/** Offset of the first character of a 1-based [line]; the end of the text when there are fewer lines. */
internal fun offsetOfLine(text: String, line: Int): Int {
    var offset = 0
    repeat((line - 1).coerceAtLeast(0)) {
        val next = text.indexOf('\n', offset)
        if (next < 0) return text.length
        offset = next + 1
    }
    return offset
}

/**
 * Numbers for the gutter, one row per visual line: a wrapped continuation of a long line
 * gets an empty row so that numbers stay next to the line they belong to.
 */
internal fun gutterText(text: String, layout: TextLayoutResult?): String {
    if (layout == null) return (1..text.count { it == '\n' } + 1).joinToString("\n")
    var number = 0
    return (0 until layout.lineCount).joinToString("\n") { visualLine ->
        val start = layout.getLineStart(visualLine)
        if (start == 0 || text.getOrNull(start - 1) == '\n') (++number).toString() else ""
    }
}

/** Monospace code field with line numbers and syntax colours. */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    runtime: ModuleRuntimeKind,
    modifier: Modifier = Modifier,
    minHeight: Dp = 280.dp,
) {
    val colors = MaterialTheme.colorScheme
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurface)
    val highlighter = remember(runtime, colors) { CodeHighlighter(runtime, colors) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = gutterText(value.text, layout?.takeIf { it.layoutInput.text.text == value.text }),
            style = style.copy(color = colors.outline, textAlign = TextAlign.End),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(withAutoIndent(value, it)) },
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = highlighter,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            onTextLayout = { layout = it },
            modifier = Modifier.weight(1f).heightIn(min = minHeight).padding(end = 8.dp),
        )
    }
}

/** A ready call the author can drop into the code. */
class Snippet(val label: String, val code: String)

internal fun snippets(runtime: ModuleRuntimeKind): List<Snippet> = if (runtime == ModuleRuntimeKind.JS) {
    listOf(
        Snippet("log", "mf.log(\"\");\n"),
        Snippet("http", "var response = mf.http({ url: \"https://\" });\nmf.log(response.status + \" \" + response.body);\n"),
        Snippet("json", "var data = JSON.parse(response.body);\n"),
        Snippet("storage", "mf.storage.write(\"file.txt\", \"text\");\nvar text = mf.storage.read(\"file.txt\");\n"),
        Snippet("config", "var config = require(\"mf/config\");\nvar value = config.get(\"key\", { ask: \"Question\" });\n"),
        Snippet("ask", "var answer = mf.ask(\"Question\");\n"),
        Snippet("notify", "mf.notify(\"Title\", \"Text\");\n"),
        Snippet("schedule", "var schedule = require(\"mf/schedule\");\nschedule.every(300, function () {\n    \n});\nschedule.run();\n"),
        Snippet("telegram", "var telegram = require(\"mf/telegram\");\nvar bot = telegram.bot();\nbot.on(\"text\", function (message) {\n    bot.reply(message, message.text);\n});\nbot.run();\n"),
        Snippet("ui", "mf.ui.show([\n    { type: \"text\", text: \"Hello\" },\n    { type: \"button\", id: \"ok\", label: \"OK\" }\n]);\nvar event = mf.ui.wait();\n"),
        Snippet("date", "mf.date(\"%Y-%m-%d %H:%M\")"),
        Snippet("sleep", "mf.sleep(1);\n"),
        Snippet("app", "mf.apps.launch(\"Calculator\");\nmf.screen.wait(\"7\", 8);\n"),
        Snippet("tap", "mf.screen.tap(100, 200);\n"),
        Snippet("click", "mf.screen.click(\"OK\");\n"),
        Snippet("screen", "mf.screen.texts().forEach(function (t) { mf.log(t.text + \" \" + t.x + \",\" + t.y); });\n"),
        Snippet("photo", "var photo = mf.camera.photo({ path: \"photo.jpg\", lens: \"back\" });\nmf.log(photo.width + \"x\" + photo.height);\n"),
    )
} else {
    listOf(
        Snippet("log", "mf.log(\"\")\n"),
        Snippet("http", "local response, err = mf.http{ url = \"https://\" }\nif response then\n    mf.log(response.status .. \" \" .. response.body)\nend\n"),
        Snippet("json", "local data = mf.json.decode(response.body)\n"),
        Snippet("storage", "mf.storage.write(\"file.txt\", \"text\")\nlocal text = mf.storage.read(\"file.txt\")\n"),
        Snippet("config", "local config = require(\"mf.config\")\nlocal value = config.get(\"key\", { ask = \"Question\" })\n"),
        Snippet("ask", "local answer = mf.ask(\"Question\")\n"),
        Snippet("notify", "mf.notify(\"Title\", \"Text\")\n"),
        Snippet("schedule", "local schedule = require(\"mf.schedule\")\nschedule.every(300, function()\n    \nend)\nschedule.run()\n"),
        Snippet("telegram", "local telegram = require(\"mf.telegram\")\nlocal bot = assert(telegram.bot())\nbot:on(\"text\", function(message)\n    bot:reply(message, message.text)\nend)\nbot:run()\n"),
        Snippet("ui", "mf.ui.show({\n    { type = \"text\", text = \"Hello\" },\n    { type = \"button\", id = \"ok\", label = \"OK\" },\n})\nlocal event = mf.ui.wait()\n"),
        Snippet("date", "mf.date(\"%Y-%m-%d %H:%M\")"),
        Snippet("sleep", "mf.sleep(1)\n"),
        Snippet("app", "mf.apps.launch(\"Calculator\")\nmf.screen.wait(\"7\", 8)\n"),
        Snippet("tap", "mf.screen.tap(100, 200)\n"),
        Snippet("click", "mf.screen.click(\"OK\")\n"),
        Snippet("screen", "for _, t in ipairs(mf.screen.texts()) do\n    mf.log(t.text .. \" \" .. t.x .. \",\" .. t.y)\nend\n"),
        Snippet("photo", "local photo = mf.camera.photo{ path = \"photo.jpg\", lens = \"back\" }\nif photo then mf.log(photo.width .. \"x\" .. photo.height) end\n"),
    )
}

/** Replaces the selection of [value] with [code] and puts the cursor after it. */
internal fun insertAtCursor(value: TextFieldValue, code: String): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    return TextFieldValue(value.text.substring(0, start) + code + value.text.substring(end), TextRange(start + code.length))
}
