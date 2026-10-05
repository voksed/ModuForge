package dev.moduforge.packer

import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import java.io.File

/** Starting point of a new module: what it is for, what it needs and its first script. */
internal class Template(
    val key: String,
    val title: String,
    val permissions: Map<Capability, String>,
    val script: String,
)

internal val TEMPLATES = listOf(
    Template(
        key = "telegram",
        title = "Telegram bot",
        permissions = linkedMapOf(
            Capability.NETWORK_OUTBOUND to "Talks to api.telegram.org.",
            Capability.FILE_SANDBOXED to "Keeps the bot token and its place in the message queue.",
            Capability.BACKGROUND_EXECUTION to "Keeps answering while the app is not on screen.",
        ),
        script = """
            -- Telegram bot. The token is asked for on the first start and kept in module storage.
            local telegram = require("mf.telegram")

            local bot = assert(telegram.bot())

            bot:command("start", function(message)
                bot:reply(message, "Hello! Send me any text.")
            end)

            bot:on("text", function(message)
                bot:reply(message, message.text)
            end)

            local _, reason = bot:run()
            mf.log("bot stopped: " .. tostring(reason))
        """,
    ),
    Template(
        key = "watcher",
        title = "Watcher: checks a web page on a schedule and notifies about changes",
        permissions = linkedMapOf(
            Capability.NETWORK_OUTBOUND to "Downloads the watched page.",
            Capability.FILE_SANDBOXED to "Remembers the address and the last seen content.",
            Capability.NOTIFICATIONS to "Tells you when the page changes.",
            Capability.BACKGROUND_EXECUTION to "Keeps checking while the app is not on screen.",
        ),
        script = """
            -- Checks a page every few minutes and notifies when its content changes.
            local config = require("mf.config")
            local schedule = require("mf.schedule")

            local url = config.get("url", { ask = "Address of the page to watch (https://...)" })
            if not url then
                mf.log("nothing to watch")
                return
            end

            schedule.every(300, function()
                local response, err = mf.http{ url = url }
                if not response then
                    mf.log("check failed: " .. tostring(err))
                    return
                end
                local previous = config.get("last")
                if previous and previous ~= response.body then
                    mf.notify("Page changed", url)
                end
                config.set("last", response.body)
            end)

            schedule.run()
        """,
    ),
    Template(
        key = "empty",
        title = "Empty script",
        permissions = emptyMap(),
        script = """
            -- Runs from top to bottom when the module starts. The API is described in docs/en/lua-api.md.
            mf.log("hello from " .. mf.name)
        """,
    ),
)

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

    val menu = TEMPLATES.mapIndexed { index, template -> "  ${index + 1}. ${template.title}" }.joinToString("\n")
    val choice = answer("What to start from\n$menu\nNumber", "1").toIntOrNull()
    val template = TEMPLATES.getOrNull((choice ?: 0) - 1) ?: throw UsageError("choose a number from 1 to ${TEMPLATES.size}")

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
