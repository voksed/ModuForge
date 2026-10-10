package dev.moduforge.core.authoring

import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import dev.moduforge.sdk.SemVer
import dev.moduforge.sdk.UiKind
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Starting point of a new script module: what it needs and its first script.
 *
 * @property key unique across languages, e.g. `lua-telegram`.
 * @property kind what the template is, shared by its versions in every language.
 */
class ModuleTemplate(
    val key: String,
    val kind: String,
    val title: String,
    val runtime: ModuleRuntimeKind,
    val permissions: Map<Capability, String>,
    val script: String,
)

object ModuleTemplates {
    const val TELEGRAM = "telegram"
    const val WATCHER = "watcher"
    const val MONITOR = "monitor"
    const val EMPTY = "empty"

    private val telegramPermissions = linkedMapOf(
        Capability.NETWORK_OUTBOUND to "Talks to api.telegram.org.",
        Capability.FILE_SANDBOXED to "Keeps the bot token and its place in the message queue.",
        Capability.BACKGROUND_EXECUTION to "Keeps answering while the app is not on screen.",
    )
    private val watcherPermissions = linkedMapOf(
        Capability.NETWORK_OUTBOUND to "Downloads the watched page.",
        Capability.FILE_SANDBOXED to "Remembers the address and the last seen content.",
        Capability.NOTIFICATIONS to "Tells you when the page changes.",
        Capability.BACKGROUND_EXECUTION to "Keeps checking while the app is not on screen.",
    )

    private val monitorPermissions = linkedMapOf(
        Capability.NETWORK_OUTBOUND to "Opens the addresses you listed, and only those.",
        Capability.FILE_SANDBOXED to "Keeps the last state of each site to notice a change.",
        Capability.NOTIFICATIONS to "Tells you when a site goes down or comes back.",
        Capability.BACKGROUND_EXECUTION to "Keeps checking while the app is not on screen.",
    )

    val ALL: List<ModuleTemplate> = listOf(
        ModuleTemplate(
            "lua-telegram", TELEGRAM, "Telegram bot", ModuleRuntimeKind.LUA, telegramPermissions,
            """
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
            "lua-watcher", WATCHER, "Watcher: checks a web page on a schedule and notifies about changes",
            ModuleRuntimeKind.LUA, watcherPermissions,
            """
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
            "lua-monitor", MONITOR, "Site monitor: tells you when one of your sites goes down or comes back",
            ModuleRuntimeKind.LUA, monitorPermissions,
            """
            -- Checks your sites every few minutes and notifies when one goes down or comes back.
            local config = require("mf.config")
            local schedule = require("mf.schedule")

            local list = config.get("sites", { ask = "Sites to watch, separated by spaces", label = "Sites" })
            if not list then
                mf.log("nothing to watch")
                return
            end

            local state = mf.json.decode(mf.storage.read("state.json") or "{}")

            schedule.every(300, function()
                for site in list:gmatch("%S+") do
                    local url = site:find("://", 1, true) and site or ("https://" .. site)
                    local response, err = mf.http{ url = url }
                    local up = response ~= nil and response.status < 500
                    local note = response and ("HTTP " .. response.status) or tostring(err)
                    mf.log((up and "up   " or "DOWN ") .. url .. " " .. note)
                    if state[url] ~= nil and state[url] ~= up then
                        mf.notify(up and "Back up" or "Down", url)
                    end
                    state[url] = up
                end
                mf.storage.write("state.json", mf.json.encode(state))
            end)
            schedule.run()
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "lua-empty", EMPTY, "Empty script", ModuleRuntimeKind.LUA, emptyMap(),
            """
            -- Runs from top to bottom when the module starts.
            mf.log("hello from " .. mf.name)
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "js-telegram", TELEGRAM, "Telegram bot", ModuleRuntimeKind.JS, telegramPermissions,
            """
            // Telegram bot. The token is asked for on the first start and kept in module storage.
            var telegram = require("mf/telegram");

            var bot = telegram.bot();

            bot.command("start", function (message) {
                bot.reply(message, "Hello! Send me any text.");
            });

            bot.on("text", function (message) {
                bot.reply(message, message.text);
            });

            try {
                bot.run();
            } catch (e) {
                mf.log("bot stopped: " + e.message);
            }
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "js-watcher", WATCHER, "Watcher: checks a web page on a schedule and notifies about changes",
            ModuleRuntimeKind.JS, watcherPermissions,
            """
            // Checks a page every few minutes and notifies when its content changes.
            var config = require("mf/config");
            var schedule = require("mf/schedule");

            var url = config.get("url", { ask: "Address of the page to watch (https://...)" });
            if (!url) {
                mf.log("nothing to watch");
            } else {
                schedule.every(300, function () {
                    var response;
                    try {
                        response = mf.http({ url: url });
                    } catch (e) {
                        mf.log("check failed: " + e.message);
                        return;
                    }
                    var previous = config.get("last");
                    if (previous && previous !== response.body) mf.notify("Page changed", url);
                    config.set("last", response.body);
                });
                schedule.run();
            }
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "js-monitor", MONITOR, "Site monitor: tells you when one of your sites goes down or comes back",
            ModuleRuntimeKind.JS, monitorPermissions,
            """
            // Checks your sites every few minutes and notifies when one goes down or comes back.
            var config = require("mf/config");
            var schedule = require("mf/schedule");

            var list = config.get("sites", { ask: "Sites to watch, separated by spaces", label: "Sites" });
            if (!list) {
                mf.log("nothing to watch");
            } else {
                var state = JSON.parse(mf.storage.read("state.json") || "{}");
                schedule.every(300, function () {
                    list.split(/\s+/).filter(Boolean).forEach(function (site) {
                        var url = site.indexOf("://") >= 0 ? site : "https://" + site;
                        var up, note;
                        try {
                            var response = mf.http({ url: url });
                            up = response.status < 500;
                            note = "HTTP " + response.status;
                        } catch (error) {
                            up = false;
                            note = String(error);
                        }
                        mf.log((up ? "up   " : "DOWN ") + url + " " + note);
                        if (state[url] !== undefined && state[url] !== up) {
                            mf.notify(up ? "Back up" : "Down", url);
                        }
                        state[url] = up;
                    });
                    mf.storage.write("state.json", JSON.stringify(state));
                });
                schedule.run();
            }
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "js-empty", EMPTY, "Empty script", ModuleRuntimeKind.JS, emptyMap(),
            """
            // Runs from top to bottom when the module starts.
            mf.log("hello from " + mf.name);
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "py-telegram", TELEGRAM, "Telegram bot", ModuleRuntimeKind.PYTHON, telegramPermissions,
            """
            # Telegram bot. The token is asked for on the first start and kept in module storage.
            import mf
            from mf import telegram

            bot = telegram.bot()


            @bot.command("start")
            def start(message, args):
                bot.reply(message, "Hello! Send me any text.")


            @bot.on("text")
            def echo(message):
                bot.reply(message, message["text"])


            try:
                bot.run()
            except mf.Error as error:
                mf.log("bot stopped: " + str(error))
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "py-watcher", WATCHER, "Watcher: checks a web page on a schedule and notifies about changes",
            ModuleRuntimeKind.PYTHON, watcherPermissions,
            """
            # Checks a page every few minutes and notifies when its content changes.
            import mf
            from mf import config, schedule

            url = config.get("url", ask="Address of the page to watch (https://...)")
            if not url:
                mf.log("nothing to watch")
            else:
                def check():
                    try:
                        response = mf.http(url)
                    except mf.Error as error:
                        mf.log("check failed: " + str(error))
                        return
                    previous = config.get("last")
                    if previous and previous != response.body:
                        mf.notify("Page changed", url)
                    config.set("last", response.body)

                schedule.every(300, check)
                schedule.run()
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "py-monitor", MONITOR, "Site monitor: tells you when one of your sites goes down or comes back",
            ModuleRuntimeKind.PYTHON, monitorPermissions,
            """
            # Checks your sites every few minutes and notifies when one goes down or comes back.
            import json
            import mf
            from mf import config, schedule

            sites = config.get("sites", ask="Sites to watch, separated by spaces", label="Sites")
            if not sites:
                mf.log("nothing to watch")
            else:
                state = json.loads(mf.storage.read("state.json") or "{}")

                def check():
                    for site in sites.split():
                        url = site if "://" in site else "https://" + site
                        try:
                            up = mf.http(url).status < 500
                            note = "reachable"
                        except mf.Error as error:
                            up = False
                            note = str(error)
                        mf.log(("up   " if up else "DOWN ") + url + " " + note)
                        if url in state and state[url] != up:
                            mf.notify("Back up" if up else "Down", url)
                        state[url] = up
                    mf.storage.write("state.json", json.dumps(state))

                schedule.every(300, check)
                schedule.run()
            """.trimIndent() + "\n",
        ),
        ModuleTemplate(
            "py-empty", EMPTY, "Empty script", ModuleRuntimeKind.PYTHON, emptyMap(),
            """
            # Runs from top to bottom when the module starts.
            import mf

            mf.log("hello from " + mf.name)
            """.trimIndent() + "\n",
        ),
    )

    fun forRuntime(runtime: ModuleRuntimeKind): List<ModuleTemplate> = ALL.filter { it.runtime == runtime }
}

/** Works out what a script needs from the calls it makes, so authors do not have to declare it. */
object ScriptAnalyzer {
    private val USES = listOf(
        Regex("""\bmf\s*\.\s*(http|connect|websocket)\b|["']mf[./]telegram["']|\bfrom\s+mf\s+import\b[^\n]*\btelegram\b|\bimport\s+mf\.telegram\b""") to Capability.NETWORK_OUTBOUND,
        Regex("""\bmf\s*\.\s*storage\b|["']mf[./](config|telegram)["']|\bfrom\s+mf\s+import\b[^\n]*\b(config|telegram)\b|\bimport\s+mf\.(config|telegram)\b""") to Capability.FILE_SANDBOXED,
        Regex("""\bmf\s*\.\s*notify\b""") to Capability.NOTIFICATIONS,
        Regex("""\bmf\s*\.\s*apps\b""") to Capability.LAUNCH_APPS,
        Regex("""\bmf\s*\.\s*camera\b""") to Capability.CAMERA,
        Regex("""\bmf\s*\.\s*screen\b""") to Capability.SCREEN_CONTROL,
        // Opening or operating another app takes ModuForge off the screen, and the host stops modules that cannot work in the background.
        Regex("""\bmf\s*\.\s*(apps|screen)\b""") to Capability.BACKGROUND_EXECUTION,
    )
    private val UI = Regex("""\bmf\s*\.\s*ui\b""")

    /** Permissions whose host services the [source] calls. Line comments are ignored. */
    fun detectPermissions(source: String, runtime: ModuleRuntimeKind = ModuleRuntimeKind.LUA): Set<Capability> {
        val code = code(source, runtime)
        return USES.filter { (pattern, _) -> pattern.containsMatchIn(code) }.map { it.second }.toSet()
    }

    /** True when the script shows an interface. */
    fun usesInterface(source: String, runtime: ModuleRuntimeKind = ModuleRuntimeKind.LUA): Boolean =
        UI.containsMatchIn(code(source, runtime))

    private fun code(source: String, runtime: ModuleRuntimeKind): String {
        // A URL inside a string contains "//"; only a comment marker at a token boundary counts.
        val comment = when (runtime) {
            ModuleRuntimeKind.JS -> Regex("""(^|\s)//.*$""")
            ModuleRuntimeKind.PYTHON -> Regex("""(^|\s)#.*$""")
            else -> Regex("""--.*$""")
        }
        return source.lineSequence().joinToString("\n") { it.replace(comment, "") }
    }
}

/**
 * Modules written or imported as plain source on the device. They carry no signature: the
 * user is the author, sees the code and can change it. Sharing one with other people goes
 * through a signed package.
 */
object LocalModules {
    const val ID_PREFIX = "local."

    /** Languages a module can be written in on the device. */
    val RUNTIMES = listOf(ModuleRuntimeKind.LUA, ModuleRuntimeKind.JS, ModuleRuntimeKind.PYTHON)

    /** Capabilities a local module can usefully declare: those with a host service behind them. */
    val AVAILABLE = listOf(
        Capability.NETWORK_OUTBOUND,
        Capability.FILE_SANDBOXED,
        Capability.BACKGROUND_EXECUTION,
        Capability.NOTIFICATIONS,
        Capability.LAUNCH_APPS,
        Capability.CAMERA,
        Capability.SCREEN_CONTROL,
    )

    fun isLocal(moduleId: String): Boolean = moduleId.startsWith(ID_PREFIX)

    /** Name of the single script of a local module in [runtime]. */
    fun entryFor(runtime: ModuleRuntimeKind): String = when (runtime) {
        ModuleRuntimeKind.JS -> "main.js"
        ModuleRuntimeKind.PYTHON -> "main.py"
        else -> "main.lua"
    }

    /** Language of a script file by its name; null when the extension is not a script's. */
    fun runtimeForFile(fileName: String): ModuleRuntimeKind? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "lua" -> ModuleRuntimeKind.LUA
        "js", "mjs" -> ModuleRuntimeKind.JS
        "py" -> ModuleRuntimeKind.PYTHON
        else -> null
    }

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
    fun manifest(
        id: String,
        name: String,
        source: String,
        selected: Set<Capability>,
        previous: ModuleManifest?,
        runtime: ModuleRuntimeKind = previous?.runtime ?: ModuleRuntimeKind.LUA,
    ): ModuleManifest {
        val version = previous?.let { SemVer.parseOrNull(it.version) }?.let { SemVer(it.major, it.minor, it.patch + 1) } ?: SemVer(1, 0, 0)
        val detected = ScriptAnalyzer.detectPermissions(source, runtime)
        return ModuleManifest(
            id = id,
            name = name.trim().take(64).ifEmpty { "Module" },
            version = version.toString(),
            sdkRange = ">=1.0.0 <2.0.0",
            entry = entryFor(runtime),
            runtime = runtime,
            permissions = AVAILABLE.filter { it in selected || it in detected },
            ui = if (ScriptAnalyzer.usesInterface(source, runtime)) UiKind.COMPOSE else UiKind.NONE,
            author = "",
            description = "",
        )
    }

    /** True when [name] can be a source file of a local module written in [runtime]. */
    fun isSourceFileName(name: String, runtime: ModuleRuntimeKind): Boolean =
        ModuleManifests.isRelativePath(name) && runtimeForFile(name) == runtime

    /** Unsigned package of a single-script module. */
    fun pack(manifest: ModuleManifest, source: String): ByteArray = pack(manifest, mapOf(manifest.entry to source))

    /**
     * Unsigned package in the layout the sandbox reads.
     *
     * @param files sources by path relative to the module root; must contain the manifest's entry.
     */
    fun pack(manifest: ModuleManifest, files: Map<String, String>): ByteArray {
        require(manifest.entry in files) { "entry script ${manifest.entry} is missing" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (mapOf(ModulePackageFormat.MANIFEST to ModuleManifests.encode(manifest).toByteArray()) +
                files.map { (path, source) -> ModulePackageFormat.CODE_PREFIX + path to source.toByteArray() }
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
