package dev.moduforge.packer

import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.core.authoring.ModuleTemplates
import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import java.io.File

/**
 * Asks the author a few questions and writes a ready-to-pack module project.
 *
 * @param ask prints a question with its default and returns the answer, or null at end of input.
 */
internal fun createProject(target: File?, ask: (question: String, default: String) -> String?): String {
    fun answer(question: String, default: String): String =
        ask(question, default)?.trim()?.ifEmpty { default } ?: default

    val name = answer("Module name", "My module")
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "module" }.let { if (it[0].isDigit()) "m$it" else it }
    val id = answer("Unique id (reverse-DNS, lowercase)", "my.$slug")
    val author = answer("Author", System.getProperty("user.name").orEmpty())
    val description = answer("What does it do (one sentence)", "")

    val languages = LocalModules.RUNTIMES
    val languageMenu = languages.mapIndexed { index, runtime -> "  ${index + 1}. ${languageName(runtime)}" }.joinToString("\n")
    val language = languages.getOrNull((answer("Language\n$languageMenu\nNumber", "1").toIntOrNull() ?: 0) - 1)
        ?: throw UsageError("choose a number from 1 to ${languages.size}")

    val templates = ModuleTemplates.forRuntime(language)
    val menu = templates.mapIndexed { index, template -> "  ${index + 1}. ${template.title}" }.joinToString("\n")
    val choice = answer("What to start from\n$menu\nNumber", "1").toIntOrNull()
    val template = templates.getOrNull((choice ?: 0) - 1) ?: throw UsageError("choose a number from 1 to ${templates.size}")

    val manifest = ModuleManifest(
        id = id,
        name = name,
        version = "0.1.0",
        sdkRange = ">=1.0.0 <2.0.0",
        entry = LocalModules.entryFor(language),
        runtime = language,
        permissions = template.permissions.keys.toList(),
        permissionReasons = template.permissions,
        author = author,
        description = description,
    )
    ModuleManifests.validate(manifest).takeIf { it.isNotEmpty() }?.let { throw UsageError(it.joinToString("; ")) }

    val dir = target ?: File(slug)
    val manifestFile = File(dir, ModulePackageFormat.MANIFEST)
    if (manifestFile.exists()) throw UsageError("$manifestFile already exists")
    dir.mkdirs()
    manifestFile.writeText(prettyManifest(manifest))
    File(dir, manifest.entry).writeText(template.script)
    writeEditorSupport(dir, language)

    return """
        Created ${dir.path}
          ${ModulePackageFormat.MANIFEST}   what the module is and which permissions it may get
          ${manifest.entry.padEnd(16)} the script that runs when the module starts

        Next: edit ${manifest.entry}, then
          mfrg run ${dir.path} --watch     try it on this computer; it restarts on every save
          mfrg push ${dir.path} --watch    the same on your phone (developer mode in the app)
          mfrg pack ${dir.path}            a signed .${ModulePackageFormat.EXTENSION} file to share
        The $EDITOR_DIRECTORY folder holds type declarations that give an editor completion for mf.
    """.trimIndent()
}

/**
 * Type declarations of `mf` and the editor settings that pick them up, so that completion works in VS Code
 * (`.mf` and `.vscode` are never packed).
 */
private fun writeEditorSupport(dir: File, language: ModuleRuntimeKind) {
    val file = when (language) {
        ModuleRuntimeKind.JS -> "mf.d.ts"
        ModuleRuntimeKind.PYTHON -> "mf.pyi"
        ModuleRuntimeKind.LUA -> "mf.lua"
        ModuleRuntimeKind.DEX -> return
    }
    val text = object {}.javaClass.getResourceAsStream("/typings/$file")?.use { it.readBytes() } ?: return
    File(dir, "$EDITOR_DIRECTORY/$file").apply { parentFile.mkdirs() }.writeBytes(text)
    val settings = when (language) {
        ModuleRuntimeKind.PYTHON -> """{ "python.analysis.extraPaths": ["$EDITOR_DIRECTORY"] }"""
        ModuleRuntimeKind.LUA -> """{ "Lua.workspace.library": ["$EDITOR_DIRECTORY"], "Lua.diagnostics.globals": ["mf"] }"""
        else -> null
    }
    if (settings != null) File(dir, ".vscode/settings.json").apply { parentFile.mkdirs() }.writeText(settings + "\n")
    if (language == ModuleRuntimeKind.JS) {
        File(dir, "jsconfig.json").writeText("""{ "include": ["*.js", "$EDITOR_DIRECTORY/mf.d.ts"] }""" + "\n")
    }
    val tasks = """
        {
          "version": "2.0.0",
          "tasks": [
            { "label": "ModuForge: run on this computer", "type": "shell", "command": "mfrg run . --watch", "problemMatcher": [] },
            { "label": "ModuForge: push to the phone", "type": "shell", "command": "mfrg push . --watch", "problemMatcher": [] }
          ]
        }
    """.trimIndent()
    File(dir, ".vscode/tasks.json").apply { parentFile.mkdirs() }.writeText(tasks + "\n")
}

/** The manifest as authors will read and edit it: one field per line, stable order. */
private fun prettyManifest(manifest: ModuleManifest): String {
    fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val reasons = manifest.permissionReasons.entries.joinToString(",\n") { "    ${quote(it.key.name)}: ${quote(it.value)}" }
    val text = buildString {
        appendLine("{")
        appendLine("  \"id\": ${quote(manifest.id)},")
        appendLine("  \"name\": ${quote(manifest.name)},")
        appendLine("  \"version\": ${quote(manifest.version)},")
        appendLine("  \"sdkRange\": ${quote(manifest.sdkRange)},")
        appendLine("  \"runtime\": \"${manifest.runtime.name.lowercase()}\",")
        appendLine("  \"entry\": ${quote(manifest.entry)},")
        appendLine("  \"author\": ${quote(manifest.author)},")
        appendLine("  \"description\": ${quote(manifest.description)},")
        appendLine("  \"permissions\": [${manifest.permissions.joinToString(", ") { quote(it.name) }}],")
        appendLine(if (reasons.isEmpty()) "  \"permissionReasons\": {}" else "  \"permissionReasons\": {\n$reasons\n  }")
        appendLine("}")
    }
    check(ModuleManifests.parse(text) == ManifestResult.Valid(manifest)) { "generated manifest does not round-trip" }
    return text
}

internal fun languageName(runtime: ModuleRuntimeKind): String = when (runtime) {
    ModuleRuntimeKind.LUA -> "Lua"
    ModuleRuntimeKind.JS -> "JavaScript"
    ModuleRuntimeKind.PYTHON -> "Python"
    ModuleRuntimeKind.DEX -> "Kotlin"
}
