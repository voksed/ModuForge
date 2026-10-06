package dev.moduforge.script

import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleRuntimeKind

/** Entry point to the script runtimes: turns the source files of a module into a runnable [Module]. */
object ScriptRuntimes {

    /** Script languages this build can execute. */
    val SUPPORTED: Set<ModuleRuntimeKind> = setOf(ModuleRuntimeKind.LUA, ModuleRuntimeKind.JS)

    /**
     * @param files module code by path relative to the package's `code/` directory.
     * @param entry path of the main script.
     * @throws IllegalArgumentException when [kind] is not a supported script runtime.
     */
    fun create(kind: ModuleRuntimeKind, files: Map<String, ByteArray>, entry: String): Module = when (kind) {
        ModuleRuntimeKind.LUA -> LuaScriptModule(files, entry)
        ModuleRuntimeKind.JS -> JsScriptModule(files, entry)
        else -> throw IllegalArgumentException("runtime ${kind.name.lowercase()} is not a supported script runtime")
    }
}
