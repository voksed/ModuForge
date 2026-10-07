package dev.moduforge.core.authoring

import dev.moduforge.sdk.ModuleManifests
import dev.moduforge.sdk.ModuleRuntimeKind

/**
 * The files and folders of a module being edited, as the editor's file tree shows them.
 *
 * Files are source texts by path relative to the module root. Folders exist implicitly as the
 * parents of files; [Tree.folders] additionally holds folders that are still empty. Nothing
 * here touches storage: every operation returns a new [Tree], or null when it must be refused.
 */
object ProjectFiles {
    const val MAX_DEPTH = 8
    const val MAX_FILES = 200

    /**
     * @property files source by path.
     * @property folders folders without files in them, which would otherwise not exist.
     */
    data class Tree(val files: Map<String, String>, val folders: Set<String> = emptySet()) {
        /** Every folder: the empty ones and the parents of all files. */
        val allFolders: Set<String>
            get() = folders + files.keys.flatMap(::parentsOf)
    }

    /** One line of the tree. */
    data class Row(val path: String, val name: String, val depth: Int, val isFolder: Boolean)

    /** Parents of [path], outermost first: `a/b/c.lua` gives `a` and `a/b`. */
    fun parentsOf(path: String): List<String> =
        path.split('/').dropLast(1).runningReduce { parent, part -> "$parent/$part" }

    /**
     * The tree flattened into the rows that are visible: folders before files, each group by
     * name, and the content of a folder only when it is in [expanded].
     */
    fun rows(tree: Tree, expanded: Set<String>): List<Row> {
        val folders = tree.allFolders
        val out = mutableListOf<Row>()
        fun walk(parent: String, depth: Int) {
            val prefix = if (parent.isEmpty()) "" else "$parent/"
            val subfolders = folders.filter { it.startsWith(prefix) && it.removePrefix(prefix).none { c -> c == '/' } && it != parent }
                .sortedBy { it.lowercase() }
            val inside = tree.files.keys.filter { it.startsWith(prefix) && it.removePrefix(prefix).none { c -> c == '/' } }
                .sortedBy { it.lowercase() }
            for (folder in subfolders) {
                out += Row(folder, folder.removePrefix(prefix), depth, isFolder = true)
                if (folder in expanded) walk(folder, depth + 1)
            }
            for (file in inside) out += Row(file, file.removePrefix(prefix), depth, isFolder = false)
        }
        walk("", 0)
        return out
    }

    /**
     * Path of a new source file typed as [input] while [folder] is selected. A name starting with
     * `/` is taken from the module root, any other from [folder]. The language's extension is
     * added when the name has none.
     *
     * @return null when the name is not usable or taken.
     */
    fun newFile(tree: Tree, input: String, folder: String, runtime: ModuleRuntimeKind): String? {
        if (tree.files.size >= MAX_FILES) return null
        val typed = input.trim()
        if (typed.isEmpty() || typed.endsWith("/")) return null
        val relative = typed.removePrefix("./")
        val joined = if (relative.startsWith("/")) relative.removePrefix("/") else listOf(folder, relative).filter { it.isNotEmpty() }.joinToString("/")
        val extension = LocalModules.entryFor(runtime).substringAfterLast('.')
        val path = if (LocalModules.runtimeForFile(joined) == null) "$joined.$extension" else joined
        if (!LocalModules.isSourceFileName(path, runtime) || path.split('/').size > MAX_DEPTH) return null
        if (path in tree.files || path in tree.allFolders) return null
        // A file cannot sit where a file is already treated as a folder.
        if (parentsOf(path).any { it in tree.files }) return null
        return path
    }

    /** Path of a new folder typed as [input] while [folder] is selected; same rules as [newFile]. */
    fun newFolder(tree: Tree, input: String, folder: String): String? {
        val typed = input.trim().trimEnd('/')
        if (typed.isEmpty()) return null
        val relative = typed.removePrefix("./")
        val path = if (relative.startsWith("/")) relative.removePrefix("/") else listOf(folder, relative).filter { it.isNotEmpty() }.joinToString("/")
        if (!ModuleManifests.isRelativePath(path) || path.split('/').size > MAX_DEPTH) return null
        if (path in tree.allFolders || path in tree.files) return null
        if (parentsOf(path).any { it in tree.files }) return null
        return path
    }

    fun withFile(tree: Tree, path: String, source: String = ""): Tree = tree.copy(files = tree.files + (path to source))

    fun withFolder(tree: Tree, path: String): Tree = tree.copy(folders = tree.folders + path)

    /**
     * Removes a file, or a folder with everything in it. The [protected] file (the entry
     * script) cannot be removed, nor a folder that holds it.
     *
     * @return null when nothing could be removed.
     */
    fun delete(tree: Tree, path: String, protected: String): Tree? {
        if (path == protected || protected.startsWith("$path/")) return null
        return when {
            path in tree.files -> tree.copy(files = tree.files - path)
            path in tree.allFolders -> Tree(
                files = tree.files.filterKeys { !it.startsWith("$path/") },
                folders = tree.folders.filterTo(mutableSetOf()) { it != path && !it.startsWith("$path/") },
            )
            else -> null
        }
    }

    /**
     * Moves a file or a folder to [to], a full path from the module root.
     *
     * @return null when the move is refused: the [protected] file would move, the target is
     *   taken, it is not a valid name, or a folder would move into itself.
     */
    fun move(tree: Tree, from: String, to: String, runtime: ModuleRuntimeKind, protected: String): Tree? {
        if (from == to || from == protected || protected.startsWith("$from/")) return null
        if (to.split('/').size > MAX_DEPTH || to.startsWith("$from/")) return null
        if (to in tree.files || to in tree.allFolders || parentsOf(to).any { it in tree.files }) return null
        return when {
            from in tree.files -> {
                if (!LocalModules.isSourceFileName(to, runtime)) return null
                tree.copy(files = tree.files.mapKeys { (path, _) -> if (path == from) to else path })
            }
            from in tree.allFolders -> {
                if (!ModuleManifests.isRelativePath(to)) return null
                fun moved(path: String) = if (path == from || path.startsWith("$from/")) to + path.removePrefix(from) else path
                Tree(
                    files = tree.files.mapKeys { (path, _) -> moved(path) },
                    folders = tree.folders.mapTo(mutableSetOf(), ::moved),
                )
            }
            else -> null
        }
    }

    /** Folder a path belongs to when it is selected: the folder itself, or the parent of a file. */
    fun folderOf(tree: Tree, path: String): String = if (path in tree.files) path.substringBeforeLast('/', "") else path
}
