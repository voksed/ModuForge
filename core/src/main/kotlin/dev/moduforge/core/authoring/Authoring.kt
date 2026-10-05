package dev.moduforge.core.authoring

import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.SemVer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Starting point of a new script module: what it needs and its first script. */
class ModuleTemplate(
    val key: String,
    val title: String,
    val permissions: Map<Capability, String>,
    val script: String,
)

object ModuleTemplates {
    val ALL: List<ModuleTemplate> = listOf(
        ModuleTemplate(
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
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
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
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            key = "empty",
            title = "Empty script",
            permissions = emptyMap(),
            script = """
                -- Runs from top to bottom when the module starts.
                mf.log("hello from " .. mf.name)
            """.trimIndent() + "\n",
        ),
    )
}

/** Works out what a script needs from the calls it makes, so authors do not have to declare it. */
object ScriptAnalyzer {
    private val USES = listOf(
        Regex("""\bmf\s*\.\s*http\b|require\s*\(?\s*["']mf\.telegram["']""") to Capability.NETWORK_OUTBOUND,
        Regex("""\bmf\s*\.\s*storage\b|require\s*\(?\s*["']mf\.(config|telegram)["']""") to Capability.FILE_SANDBOXED,
        Regex("""\bmf\s*\.\s*notify\b""") to Capability.NOTIFICATIONS,
    )

    /** Permissions whose host services the Lua [source] calls. Comments are ignored. */
    fun detectPermissions(source: String): Set<Capability> {
        val code = source.lineSequence().joinToString("\n") { it.substringBefore("--") }
        return USES.filter { (pattern, _) -> pattern.containsMatchIn(code) }.map { it.second }.toSet()
    }
}

/**
 * Modules written or imported as plain source on the device. They carry no signature: the
 * user is the author, sees the code and can change it. Sharing one with other people goes
 * through a signed package.
 */
object LocalModules {
    const val ID_PREFIX = "local."
    const val ENTRY = "main.lua"

    /** Capabilities a local module can usefully declare: those with a host service behind them. */
    val AVAILABLE = listOf(
        Capability.NETWORK_OUTBOUND,
        Capability.FILE_SANDBOXED,
        Capability.BACKGROUND_EXECUTION,
        Capability.NOTIFICATIONS,
    )

    fun isLocal(moduleId: String): Boolean = moduleId.startsWith(ID_PREFIX)

    /** A valid, unused id for a module called [name]. */
    fun idFor(name: String, taken: (String) -> Boolean): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "").take(40).ifEmpty { "module" }
        val base = ID_PREFIX + if (slug[0].isDigit()) "m$slug" else slug
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { !taken(it) }
    }

    /**
     * Manifest of a local module. Declares what the user selected plus what the code uses.
     *
     * @param previous manifest being replaced; its version is advanced so that saves are told apart in the audit log.
     */
    fun manifest(id: String, name: String, source: String, selected: Set<Capability>, previous: ModuleManifest?): ModuleManifest {
        val version = previous?.let { SemVer.parseOrNull(it.version) }?.let { SemVer(it.major, it.minor, it.patch + 1) } ?: SemVer(1, 0, 0)
        val permissions = AVAILABLE.filter { it in selected || it in ScriptAnalyzer.detectPermissions(source) }
        return ModuleManifest(
            id = id,
            name = name.trim().take(64).ifEmpty { "Module" },
            version = version.toString(),
            sdkRange = ">=1.0.0 <2.0.0",
            entry = ENTRY,
            runtime = ModuleRuntimeKind.LUA,
            permissions = permissions,
            author = "",
            description = "",
        )
    }

    /** Unsigned package of a single-script module, in the layout the sandbox reads. */
    fun pack(manifest: ModuleManifest, source: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf(
                ModulePackageFormat.MANIFEST to ModuleManifests.encode(manifest).toByteArray(),
                ModulePackageFormat.CODE_PREFIX + ENTRY to source.toByteArray(),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
