package dev.moduforge.packer

import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind
import java.io.File

private val HEADER_LINE = Regex("""^\s*(?:--|//|#)\s*@(\w+)\s*(.*?)\s*$""")
private const val KEY_PART = 8
private val COMMENT_LINE = Regex("""^\s*(?:--|//|#)""")

/**
 * The manifest of a single script, worked out from its code and from the comment lines at its top:
 *
 * ```
 * -- @name Water reminder
 * -- @id com.example.water      (optional)
 * -- @version 1.2.0             (optional)
 * -- @description Reminds you to drink.
 * -- @author Your Name
 * -- @permission NOTIFICATIONS  (optional: permissions the code uses are found by themselves)
 * ```
 *
 * `//` and `#` start the lines in JavaScript and Python. The header ends with the first line that is
 * not a comment.
 *
 * @param sources the text of the script and of the files it may use, to find the permissions in.
 * @param keyFingerprint fingerprint of the key that will sign the package; without an `@id` it makes the id unique
 * to the author. `mfrg run` passes null: nothing is published, so the local id is fine.
 * @throws UsageError when the header names an unknown permission.
 */
internal fun scriptManifest(script: File, sources: String, keyFingerprint: String? = null): ModuleManifest {
    val runtime = LocalModules.runtimeForFile(script.name) ?: throw UsageError("${script.name} is not a .lua, .js or .py script")
    val header = linkedMapOf<String, String>()
    val permissions = mutableSetOf<Capability>()
    for (line in script.readLines()) {
        if (line.isBlank()) continue
        if (!COMMENT_LINE.containsMatchIn(line)) break
        val match = HEADER_LINE.find(line) ?: continue
        val (key, value) = match.destructured
        if (key == "permission") {
            permissions += Capability.entries.firstOrNull { it.name == value.trim() }
                ?: throw UsageError("unknown permission '$value' in the header of ${script.name}")
        } else {
            header.putIfAbsent(key, value)
        }
    }
    val name = header["name"]?.takeIf { it.isNotBlank() } ?: script.nameWithoutExtension
    // Packages of two authors must not share an id: without @id a packed script's id carries a part of the key.
    val id = header["id"]?.takeIf { it.isNotBlank() }
        ?: keyFingerprint?.let { "script." + LocalModules.idFor(name) { false }.removePrefix(LocalModules.ID_PREFIX) + ".k" + it.take(KEY_PART) }
        ?: LocalModules.idFor(name) { false }
    val base = LocalModules.manifest(id, name, sources, permissions, null, runtime)
    val manifest = base.copy(
        version = header["version"]?.takeIf { it.isNotBlank() } ?: base.version,
        author = header["author"].orEmpty(),
        description = header["description"].orEmpty(),
    )
    ModuleManifests.validate(manifest).takeIf { it.isNotEmpty() }?.let {
        throw UsageError("the header of ${script.name} is not valid: ${it.joinToString("; ")}")
    }
    return manifest
}

private val LUA_REQUIRE = Regex("""\brequire\s*\(?\s*["']([^"']+)["']""")
private val JS_REQUIRE = Regex("""\brequire\s*\(\s*["']([^"']+)["']\s*\)""")
private val PY_IMPORT = Regex("""^\s*import\s+([\w.]+(?:\s*,\s*[\w.]+)*)""", RegexOption.MULTILINE)
private val PY_FROM = Regex("""^\s*from\s+(\.*[\w.]*)\s+import\s+([\w, *]+)""", RegexOption.MULTILINE)

/**
 * The text of [main] and of the [files] it reaches through `require` or `import`, directly or through each
 * other. Only these decide the permissions of a single script: other scripts that happen to lie in the same
 * folder do not widen what it may do.
 *
 * @param files the other scripts of the folder by path relative to it.
 */
internal fun reachableSources(main: String, files: Map<String, ByteArray>, runtime: ModuleRuntimeKind): List<String> {
    val queue = ArrayDeque<Pair<String, String>>() // path ("" for the main script) and its text
    queue += "" to main
    val seen = mutableSetOf<String>()
    val result = mutableListOf(main)
    while (queue.isNotEmpty()) {
        val (path, text) = queue.removeFirst()
        val directory = path.substringBeforeLast('/', "")
        for (candidate in references(text, directory, runtime)) {
            val resolved = candidate.firstOrNull { it in files } ?: continue
            if (!seen.add(resolved)) continue
            val body = files.getValue(resolved).decodeToString()
            result += body
            queue += resolved to body
        }
    }
    return result
}

/** Joins path parts and resolves `.` and `..`. */
private fun joinPath(vararg parts: String): String {
    val out = mutableListOf<String>()
    for (part in parts.flatMap { it.split('/') }) {
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out += part
        }
    }
    return out.joinToString("/")
}

/** For each reference in [text], the file names it may mean, best first. */
private fun references(text: String, directory: String, runtime: ModuleRuntimeKind): List<List<String>> = when (runtime) {
    ModuleRuntimeKind.LUA -> LUA_REQUIRE.findAll(text).map { m ->
        val name = m.groupValues[1].replace('.', '/')
        listOf("$name.lua", "$name/init.lua")
    }.toList()
    ModuleRuntimeKind.JS -> JS_REQUIRE.findAll(text).map { m ->
        val name = m.groupValues[1]
        val base = if (name.startsWith(".")) joinPath(directory, name) else joinPath(name)
        listOf(base, "$base.js", "$base/index.js")
    }.toList()
    ModuleRuntimeKind.PYTHON -> buildList {
        for (m in PY_IMPORT.findAll(text)) {
            for (name in m.groupValues[1].split(',')) {
                val path = name.trim().replace('.', '/')
                add(listOf("$path.py", "$path/__init__.py"))
            }
        }
        for (m in PY_FROM.findAll(text)) {
            val module = m.groupValues[1]
            val dots = module.takeWhile { it == '.' }.length
            val rest = module.drop(dots).replace('.', '/')
            // A relative import starts at the folder of the importing file, one level up for each extra dot.
            val up = "../".repeat(maxOf(0, dots - 1))
            val base = if (dots == 0) rest else joinPath(directory, up, rest)
            add(listOf("$base.py", "$base/__init__.py"))
            // "from pkg import module" may name a file of the package.
            for (name in m.groupValues[2].split(',')) {
                val item = name.trim()
                if (item.isEmpty() || item == "*") continue
                add(listOf(joinPath(base, "$item.py"), joinPath(base, item, "__init__.py")))
            }
        }
    }
    else -> emptyList()
}
