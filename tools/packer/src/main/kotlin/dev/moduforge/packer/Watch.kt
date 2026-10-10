package dev.moduforge.packer

import java.io.File
import kotlin.concurrent.thread

private const val POLL_MS = 400L

/**
 * What `--watch` compares: the path, size and time of every file of the project (or of the folder of
 * a single script), without caches, VCS folders and the directory `mfrg run` keeps its storage in.
 */
internal fun fingerprint(target: File): Map<String, Long> {
    val root = if (target.isDirectory) target else target.absoluteFile.parentFile
    return root.walkTopDown()
        .onEnter { it == root || it.name !in SKIPPED_DIRECTORIES }
        .filter { it.isFile && it.extension != "mfrg" }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.lastModified() * 31 + it.length() }
}

/**
 * `mfrg push --watch`: pushes now and again after every save. Only the newest push prints, so
 * the output of a module that was replaced does not mix with the output of its successor.
 */
internal fun watchPush(dir: File, options: Options, print: (String) -> Unit): String {
    print("watching $dir: the module is pushed again when a file is saved (Ctrl+C to stop)")
    var generation = 0
    while (true) {
        val before = fingerprint(dir)
        val mine = ++generation
        // Following the output blocks, so each push has its own thread.
        thread(isDaemon = true, name = "push-$mine") {
            try {
                push(dir, options, { line -> if (mine == generation) print(line) })
            } catch (e: UsageError) {
                if (mine == generation) print("error: ${e.message}")
            }
        }
        while (fingerprint(dir) == before) Thread.sleep(POLL_MS)
        // A save is often several writes in a row.
        Thread.sleep(POLL_MS)
        print("--- changed, pushing again ---")
    }
}
