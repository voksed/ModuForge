package dev.moduforge.packer

import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.core.pkg.ModulePackageVerifier
import dev.moduforge.core.pkg.ModulePackageWriter
import dev.moduforge.core.pkg.PackageCheck
import dev.moduforge.core.pkg.SigningKey
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifests
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private const val USAGE = """mfrg - builds ModuForge module packages

  mfrg new [dir]
      Asks a few questions and creates a ready-to-pack module project.

  mfrg pack <dir> [--key <key-file>] [--out <file.mfrg>] [--dex <apk-or-dex>]
      Packs <dir> (moduforge.json plus every other file as module code) into a signed package.
      Session files, .env files and VCS/cache directories are left out.
      Without --key your personal key is used and created on first use (~/.moduforge/key.json).

  mfrg keygen <key-file>
      Creates a signing key in a place of your choice.

  mfrg init <dir> --id <reverse.dns.id> --runtime <lua|js|python|dex> --entry <script-or-class> [--name <name>]
      Writes only a moduforge.json template into <dir>.

  mfrg verify <file.mfrg>
      Checks a package and prints its manifest summary and signer."""

/** Thrown for mistakes in the invocation or the input; reported without a stack trace. */
class UsageError(message: String) : Exception(message)

fun main(args: Array<String>) {
    try {
        println(run(args.toList()))
    } catch (e: UsageError) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}

/**
 * Executes one command and returns its report.
 *
 * @param readLine source of the author's answers for interactive commands.
 */
fun run(args: List<String>, readLine: () -> String? = ::readlnOrNull): String {
    val options = Options(args.drop(1))
    return when (args.firstOrNull()) {
        "new" -> createProject(options.positionalOrNull(0)?.let(::File)) { question, default ->
            print(if (default.isEmpty()) "$question: " else "$question [$default]: ")
            System.out.flush()
            readLine()
        }
        "keygen" -> keygen(File(options.positional(0, "key file")))
        "init" -> init(File(options.positional(0, "directory")), options)
        "pack" -> pack(File(options.positional(0, "directory")), options)
        "verify" -> verify(File(options.positional(0, "package file")))
        else -> USAGE
    }
}

private class Options(args: List<String>) {
    private val positional = mutableListOf<String>()
    private val named = mutableMapOf<String, String>()

    init {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg.startsWith("--")) {
                named[arg.removePrefix("--")] = args.getOrNull(index + 1) ?: throw UsageError("$arg needs a value")
                index += 2
            } else {
                positional += arg
                index++
            }
        }
    }

    fun positional(index: Int, what: String): String = positional.getOrNull(index) ?: throw UsageError("missing $what")

    fun positionalOrNull(index: Int): String? = positional.getOrNull(index)

    fun optional(name: String): String? = named[name]

    fun required(name: String): String = named[name] ?: throw UsageError("missing --$name")
}

/** Where the author's key lives when none is named: `MODUFORGE_KEY`, else `~/.moduforge/key.json`. */
private fun defaultKeyFile(): File =
    System.getenv("MODUFORGE_KEY")?.let(::File) ?: File(System.getProperty("user.home"), ".moduforge/key.json")

private fun keygen(file: File): String {
    if (file.exists()) throw UsageError("$file already exists; refusing to overwrite a key")
    val key = SigningKey.generate()
    file.absoluteFile.parentFile?.mkdirs()
    file.writeText(key.encode())
    return "Signing key written to $file\nFingerprint: ${key.fingerprint}"
}

private fun init(dir: File, options: Options): String {
    val manifest = File(dir, ModulePackageFormat.MANIFEST)
    if (manifest.exists()) throw UsageError("$manifest already exists")
    val id = options.required("id")
    val text = """
        {
          "id": "$id",
          "name": "${options.optional("name") ?: id.substringAfterLast('.')}",
          "version": "0.1.0",
          "sdkRange": ">=1.0.0 <2.0.0",
          "runtime": "${options.required("runtime")}",
          "entry": "${options.required("entry")}",
          "author": "",
          "description": "",
          "permissions": [],
          "permissionReasons": {}
        }
    """.trimIndent() + "\n"
    (ModuleManifests.parse(text) as? ManifestResult.Invalid)?.let { throw UsageError(it.problems.joinToString("; ")) }
    dir.mkdirs()
    manifest.writeText(text)
    return "Wrote $manifest"
}

private val SKIPPED_DIRECTORIES = setOf(".git", ".hg", ".svn", ".idea", ".vscode", "__pycache__", "node_modules", "venv", ".venv")
private val SECRET_FILE = Regex("""(?i).*\.(session|session-journal)$|^\.env(\..*)?$""")

private fun pack(dir: File, options: Options): String {
    val manifestFile = File(dir, ModulePackageFormat.MANIFEST)
    if (!manifestFile.isFile) throw UsageError("$manifestFile not found; create it with 'mfrg init'")
    val manifestJson = manifestFile.readText()
    val manifest = when (val parsed = ModuleManifests.parse(manifestJson)) {
        is ManifestResult.Valid -> parsed.manifest
        is ManifestResult.Invalid -> throw UsageError("invalid manifest: ${parsed.problems.joinToString("; ")}")
    }
    val keyFile = options.optional("key")?.let(::File) ?: defaultKeyFile()
    val createdKey = !keyFile.exists() && options.optional("key") == null
    if (createdKey) {
        keyFile.absoluteFile.parentFile?.mkdirs()
        keyFile.writeText(SigningKey.generate().encode())
    }
    val key = try {
        SigningKey.decode(keyFile.readText())
    } catch (e: Exception) {
        throw UsageError("cannot read signing key $keyFile: ${e.message}")
    }
    val output = File(options.optional("out") ?: "${manifest.id}-${manifest.version}.${ModulePackageFormat.EXTENSION}").absoluteFile

    val files = sortedMapOf<String, ByteArray>()
    val skippedSecrets = mutableListOf<String>()
    dir.walkTopDown()
        .onEnter { it == dir || it.name !in SKIPPED_DIRECTORIES }
        .filter { it.isFile && it != manifestFile && it.absoluteFile != output && it.extension != ModulePackageFormat.EXTENSION }
        .forEach { file ->
            val path = file.relativeTo(dir).invariantSeparatorsPath
            when {
                SECRET_FILE.matches(file.name) -> skippedSecrets += path
                !ModuleManifests.isRelativePath(path) ->
                    throw UsageError("unsupported file name: $path (allowed: letters, digits and _ . @ + -)")
                else -> files[ModulePackageFormat.CODE_PREFIX + path] = file.readBytes()
            }
        }
    options.optional("dex")?.let { files += readDex(File(it)) }

    try {
        output.parentFile?.mkdirs()
        output.outputStream().use { ModulePackageWriter.write(it, manifestJson, files, key) }
    } catch (e: IllegalArgumentException) {
        output.delete()
        throw UsageError(e.message ?: "package could not be built")
    }

    return buildString {
        appendLine("Packed ${manifest.name} ${manifest.version} (${manifest.runtime.name.lowercase()}) into $output")
        appendLine("Files: ${files.size}, signer: ${key.fingerprint}")
        appendLine("Permissions: ${manifest.permissions.joinToString().ifEmpty { "none" }}")
        if (createdKey) {
            appendLine("Created your signing key: $keyFile")
            appendLine("Keep this file private and back it up: updates must be signed with the same key.")
        }
        if (skippedSecrets.isNotEmpty()) {
            appendLine("Left out (credentials must not travel inside a package): ${skippedSecrets.joinToString()}")
        }
    }.trimEnd()
}

/** Collects `classes*.dex` from an APK, or takes a single dex file. */
private fun readDex(source: File): Map<String, ByteArray> {
    if (!source.isFile) throw UsageError("$source not found")
    if (source.extension == "dex") return mapOf(ModulePackageFormat.CODE_PREFIX + "classes.dex" to source.readBytes())
    val dex = ZipFile(source).use { zip ->
        zip.entries().asSequence()
            .filter { Regex("""classes\d*\.dex""").matches(it.name) }
            .associate { ModulePackageFormat.CODE_PREFIX + it.name to zip.getInputStream(it).readBytes() }
    }
    if (dex.isEmpty()) throw UsageError("$source contains no classes.dex")
    return dex
}

private fun verify(file: File): String = when (val check = ModulePackageVerifier.verify(file)) {
    is PackageCheck.Invalid -> throw UsageError("invalid package: ${check.reason}")
    is PackageCheck.Valid -> with(check.manifest) {
        "$name $version ($id)\nRuntime: ${runtime.name.lowercase()}, entry: $entry\n" +
            "Permissions: ${permissions.joinToString().ifEmpty { "none" }}\nSigner: ${check.signer}"
    }
}
