package dev.moduforge.packer

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

    val menu = ModuleTemplates.ALL.mapIndexed { index, template -> "  ${index + 1}. ${template.title}" }.joinToString("\n")
    val choice = answer("What to start from\n$menu\nNumber", "1").toIntOrNull()
    val template = ModuleTemplates.ALL.getOrNull((choice ?: 0) - 1) ?: throw UsageError("choose a number from 1 to ${ModuleTemplates.ALL.size}")

    val manifest = ModuleManifest(
        id = id,
        name = name,
        version = "0.1.0",
        sdkRange = ">=1.0.0 <2.0.0",
        entry = "main.lua",
        runtime = ModuleRuntimeKind.LUA,
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
    File(dir, "main.lua").writeText(template.script.trimIndent() + "\n")

    return """
        Created ${dir.path}
          ${ModulePackageFormat.MANIFEST}   what the module is and which permissions it may get
          main.lua         the script that runs when the module starts

        Next: edit main.lua, then run
          mfrg pack ${dir.path}
        and import the resulting .${ModulePackageFormat.EXTENSION} file in the app.
    """.trimIndent()
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
        appendLine("  \"runtime\": \"lua\",")
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
