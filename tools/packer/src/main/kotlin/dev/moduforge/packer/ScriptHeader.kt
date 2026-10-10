package dev.moduforge.packer

import dev.moduforge.core.authoring.LocalModules
import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import java.io.File

private val HEADER_LINE = Regex("""^\s*(?:--|//|#)\s*@(\w+)\s*(.*?)\s*$""")
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
 * @throws UsageError when the header names an unknown permission.
 */
internal fun scriptManifest(script: File, sources: String): ModuleManifest {
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
    val id = header["id"]?.takeIf { it.isNotBlank() } ?: LocalModules.idFor(name) { false }
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
