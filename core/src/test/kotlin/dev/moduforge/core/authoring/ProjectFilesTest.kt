package dev.moduforge.core.authoring

import dev.moduforge.core.authoring.ProjectFiles.Tree
import dev.moduforge.sdk.ModuleRuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilesTest {

    private val lua = ModuleRuntimeKind.LUA
    private val js = ModuleRuntimeKind.JS

    private fun tree(vararg paths: String, folders: Set<String> = emptySet()) = Tree(paths.associateWith { "-- $it" }, folders)

    private fun names(tree: Tree, expanded: Set<String>) = ProjectFiles.rows(tree, expanded).map { "${"  ".repeat(it.depth)}${if (it.isFolder) "[" else ""}${it.name}${if (it.isFolder) "]" else ""}" }

    @Test
    fun `rows list folders first and show a folder's content only when it is open`() {
        val t = tree("main.lua", "lib/util.lua", "lib/net/http.lua", "zeta.lua", "Alpha.lua", folders = setOf("empty"))
        assertEquals(listOf("[empty]", "[lib]", "Alpha.lua", "main.lua", "zeta.lua"), names(t, emptySet()))
        assertEquals(
            listOf("[empty]", "[lib]", "  [net]", "  util.lua", "Alpha.lua", "main.lua", "zeta.lua"),
            names(t, setOf("lib")),
        )
        assertEquals(
            listOf("[empty]", "[lib]", "  [net]", "    http.lua", "  util.lua", "Alpha.lua", "main.lua", "zeta.lua"),
            names(t, setOf("lib", "lib/net")),
        )
    }

    @Test
    fun `a new file is taken from the selected folder, from the root with a slash, and gets the extension`() {
        val t = tree("main.lua", "lib/util.lua")
        assertEquals("lib/helpers.lua", ProjectFiles.newFile(t, "helpers", "lib", lua))
        assertEquals("helpers.lua", ProjectFiles.newFile(t, "/helpers", "lib", lua))
        assertEquals("lib/deep/x.lua", ProjectFiles.newFile(t, "deep/x.lua", "lib", lua))
        assertEquals("a.js", ProjectFiles.newFile(tree("main.js"), " ./a ", "", js))
    }

    @Test
    fun `a new file is refused when the name is unusable or taken`() {
        val t = tree("main.lua", "lib/util.lua")
        assertNull(ProjectFiles.newFile(t, "", "", lua))
        assertNull(ProjectFiles.newFile(t, "lib/", "", lua))
        assertNull(ProjectFiles.newFile(t, "main", "", lua))
        assertNull(ProjectFiles.newFile(t, "util", "lib", lua))
        assertNull(ProjectFiles.newFile(t, "../x", "", lua))
        assertNull(ProjectFiles.newFile(t, "bad name", "", lua))
        assertNull(ProjectFiles.newFile(t, "x.js", "", lua))
        assertNull(ProjectFiles.newFile(t, "lib", "", lua).takeIf { false })
        assertNull(ProjectFiles.newFile(t, "main.lua/x", "", lua))
        assertNull(ProjectFiles.newFile(t, "a/b/c/d/e/f/g/h/i", "", lua))
        assertNull(ProjectFiles.newFile(tree("lib.lua", folders = setOf("lib")), "lib.lua/x", "", lua))
    }

    @Test
    fun `a file cannot take the name of a folder`() {
        val t = tree("lib/util.lua")
        assertNull(ProjectFiles.newFile(t, "lib.lua", "", lua).let { if (it == "lib.lua") null else it })
        val clash = Tree(mapOf("main.lua" to ""), folders = setOf("x.lua"))
        assertNull(ProjectFiles.newFile(clash, "x.lua", "", lua))
    }

    @Test
    fun `folders are created below the selected one and refused when they clash`() {
        val t = tree("main.lua", "lib/util.lua", folders = setOf("data"))
        assertEquals("lib/net", ProjectFiles.newFolder(t, "net", "lib"))
        assertEquals("net", ProjectFiles.newFolder(t, "/net", "lib"))
        assertEquals("a/b", ProjectFiles.newFolder(t, "a/b/", ""))
        assertNull(ProjectFiles.newFolder(t, "lib", ""))
        assertNull(ProjectFiles.newFolder(t, "data", ""))
        assertNull(ProjectFiles.newFolder(t, "main.lua", ""))
        assertNull(ProjectFiles.newFolder(t, "main.lua/inner", ""))
        assertNull(ProjectFiles.newFolder(t, "", ""))
        assertNull(ProjectFiles.newFolder(t, "../up", ""))
        assertNull(ProjectFiles.newFolder(t, "with space", ""))
    }

    @Test
    fun `an empty folder lives until a file is put in it`() {
        var t = tree("main.lua")
        t = ProjectFiles.withFolder(t, "lib")
        assertTrue("lib" in t.allFolders)
        t = ProjectFiles.withFile(t, "lib/util.lua")
        assertEquals(setOf("lib"), t.allFolders)
    }

    @Test
    fun `deleting a folder removes everything inside and the entry script is safe`() {
        val t = tree("main.lua", "lib/util.lua", "lib/net/http.lua", "libx.lua", folders = setOf("lib/empty", "keep"))
        val without = ProjectFiles.delete(t, "lib", "main.lua")!!
        assertEquals(setOf("main.lua", "libx.lua"), without.files.keys)
        assertEquals(setOf("keep"), without.allFolders)
        assertEquals(setOf("main.lua", "lib/net/http.lua", "libx.lua"), ProjectFiles.delete(t, "lib/util.lua", "main.lua")!!.files.keys)
        assertNull(ProjectFiles.delete(t, "main.lua", "main.lua"))
        assertNull(ProjectFiles.delete(tree("src/main.lua"), "src", "src/main.lua"))
        assertNull(ProjectFiles.delete(t, "nothing", "main.lua"))
    }

    @Test
    fun `moving a file renames it and keeps its text`() {
        val t = Tree(mapOf("main.lua" to "m", "a.lua" to "text"))
        val moved = ProjectFiles.move(t, "a.lua", "lib/b.lua", lua, "main.lua")!!
        assertEquals(mapOf("main.lua" to "m", "lib/b.lua" to "text"), moved.files)
        assertNull(ProjectFiles.move(t, "a.lua", "a.js", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "a.lua", "main.lua", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "main.lua", "start.lua", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "a.lua", "a.lua", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "a.lua", "main.lua/x.lua", lua, "main.lua"))
    }

    @Test
    fun `moving a folder moves everything below it, empty folders included`() {
        val t = Tree(mapOf("main.lua" to "", "lib/util.lua" to "u", "lib/net/http.lua" to "h"), setOf("lib/empty"))
        val moved = ProjectFiles.move(t, "lib", "src/core", lua, "main.lua")!!
        assertEquals(setOf("main.lua", "src/core/util.lua", "src/core/net/http.lua"), moved.files.keys)
        assertTrue("src/core/empty" in moved.allFolders)
        assertEquals("u", moved.files["src/core/util.lua"])
        assertNull(ProjectFiles.move(t, "lib", "lib/inner", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "lib", "lib", lua, "main.lua"))
        assertNull(ProjectFiles.move(t, "lib/net", "lib", lua, "main.lua"))
        assertNull(ProjectFiles.move(tree("src/main.lua"), "src", "code", lua, "src/main.lua"))
        assertNull(ProjectFiles.move(t, "lib", "bad name", lua, "main.lua"))
    }

    @Test
    fun `the folder of a selection is the folder itself or the parent of a file`() {
        val t = tree("main.lua", "lib/util.lua", folders = setOf("data"))
        assertEquals("lib", ProjectFiles.folderOf(t, "lib/util.lua"))
        assertEquals("lib", ProjectFiles.folderOf(t, "lib"))
        assertEquals("", ProjectFiles.folderOf(t, "main.lua"))
        assertEquals("data", ProjectFiles.folderOf(t, "data"))
    }
}
