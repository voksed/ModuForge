package dev.moduforge.packer

import dev.moduforge.core.authoring.InstallLinks
import dev.moduforge.core.pkg.ModulePackageFormat
import dev.moduforge.core.pkg.ModulePackageVerifier
import dev.moduforge.core.pkg.ModulePackageWriter
import dev.moduforge.core.pkg.PackageCheck
import dev.moduforge.core.pkg.SigningKey
import dev.moduforge.sdk.ManifestResult
import dev.moduforge.sdk.ModuleManifest
import dev.moduforge.sdk.ModuleManifests
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private const val USAGE = """mfrg - builds ModuForge module packages

  mfrg new [dir]
      Asks a few questions and creates a ready-to-pack module project.

  mfrg run <dir-or-script> [--watch] [--allow-local] [--deny <PERMISSION>[,<PERMISSION>...]]
      Runs a Lua, JavaScript or Python module on this computer, with real network access and its
      storage in .mfrg-run/ next to the code. Permissions declared in the manifest count as
      granted; --deny shows how the module behaves when the user refuses one. A single script
      needs no manifest. With --watch the module restarts whenever a file is saved. Press
      Ctrl+C to stop.

  mfrg push [dir] [--watch] [--token <token>] [--host <phone address>|usb] [--no-follow]
      Packs the module, sends it to the app on your phone, which installs it and restarts the
      module, and prints the module's output. Turn on developer mode in the app's settings
      first; it shows the token. Over USB nothing else is needed (adb forwards the port); for
      Wi-Fi pass the phone's address. Token and address are remembered after the first push.
      With --watch it pushes again whenever a file is saved.

  mfrg pack <dir|script> [--key <key-file>] [--out <file.mfrg>] [--dex <apk-or-dex>]
      Packs <dir> (moduforge.json plus every other file as module code) into a signed package.
      A single .lua, .js or .py script packs without a project: its name, version and
      description come from comment lines at its top (-- @name My module, -- @version 1.0.0,
      -- @description ..., -- @author ..., -- @permission NOTIFICATIONS; `//` or `#` in JS and
      Python), and the permissions are worked out from the code.
      Session files, .env files and VCS/cache directories are left out.
      Without --key your personal key is used and created on first use (~/.moduforge/key.json).

  mfrg link <file.mfrg> <https-address-of-the-file>
      Prints an install link for a package you uploaded somewhere. Opening the link on a phone
      (as text or as a QR code made from it) downloads the package and checks that it is
      signed with your key, so users do not compare fingerprints by eye.

  mfrg keygen <key-file>
      Creates a signing key in a place of your choice.

  mfrg init <dir> --id <reverse.dns.id> --runtime <lua|js|python|dex> --entry <script-or-class> [--name <name>]
      Writes only a moduforge.json template into <dir>.

  mfrg verify <file.mfrg>
      Checks a package and prints its manifest summary and signer."""

/** Thrown for mistakes in the invocation or the input; reported without a stack trace. */
class UsageError(message: String) : Exception(message)

/** A module script that failed. Its message was shown while it ran, so only the exit code is reported. */
class ScriptFailed(message: String) : Exception(message)

fun main(args: Array<String>) {
    try {
        run(args.toList()).takeIf { it.isNotEmpty() }?.let(::println)
    } catch (e: UsageError) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: ScriptFailed) {
        exitProcess(1)
    }
}

/**
 * Executes one command and returns its report.
 *
 * @param readLine source of the author's answers for interactive commands.
 * @param print receives the live output of a module run with `mfrg run`.
 */
fun run(args: List<String>, readLine: () -> String? = ::readlnOrNull, print: (String) -> Unit = ::println): String {
    val options = Options(args.drop(1))
    return when (args.firstOrNull()) {
        "run" -> runModule(File(options.positional(0, "module directory or script")), options, readLine, print)
        "new" -> createProject(options.positionalOrNull(0)?.let(::File)) { question, default ->
            kotlin.io.print(if (default.isEmpty()) "$question: " else "$question [$default]: ")
            System.out.flush()
            readLine()
        }
        "keygen" -> keygen(File(options.positional(0, "key file")))
        "init" -> init(File(options.positional(0, "directory")), options)
        "pack" -> pack(File(options.positional(0, "directory")), options)
        "push" -> {
            val dir = File(options.positionalOrNull(0) ?: ".")
            if (options.switch("watch")) watchPush(dir, options, print) else push(dir, options, print)
        }
        "verify" -> verify(File(options.positional(0, "package file")))
        "link" -> link(File(options.positional(0, "package file")), options.positional(1, "https address of the uploaded package"))
        else -> USAGE
    }
}

internal class Options(args: List<String>) {
    private val positional = mutableListOf<String>()
    private val named = mutableMapOf<String, String>()
    private val switches = mutableSetOf<String>()

    init {
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg.removePrefix("--") in SWITCHES) {
                switches += arg.removePrefix("--")
                index++
            } else if (arg.startsWith("--")) {
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

    fun switch(name: String): Boolean = name in switches

    private companion object {
        /** Options that take no value. */
        val SWITCHES = setOf("allow-local", "no-follow", "watch")
    }
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

/** Directory `mfrg run` keeps a module's storage in; never part of a package. */
internal const val RUN_DIRECTORY = ".mfrg-run"

/** Where `mfrg new` puts the type declarations for editors; never part of a package. */
internal const val EDITOR_DIRECTORY = ".mf"

internal val SKIPPED_DIRECTORIES =
    setOf(".git", ".hg", ".svn", ".idea", ".vscode", "__pycache__", "node_modules", "venv", ".venv", RUN_DIRECTORY, EDITOR_DIRECTORY)
private val SECRET_FILE = Regex("""(?i).*\.(session|session-journal)$|^\.env(\..*)?$""")

/**
 * Files of a module project by path relative to [dir], without the manifest, credentials,
 * caches and earlier packages. Names of left-out credential files are added to [skippedSecrets].
 */
internal fun projectFiles(dir: File, skippedSecrets: MutableList<String> = mutableListOf(), exclude: File? = null): Map<String, ByteArray> {
    val manifestFile = File(dir, ModulePackageFormat.MANIFEST)
    val files = sortedMapOf<String, ByteArray>()
    dir.walkTopDown()
        .onEnter { it == dir || it.name !in SKIPPED_DIRECTORIES }
        .filter { it.isFile && it != manifestFile && it.absoluteFile != exclude && it.extension != ModulePackageFormat.EXTENSION && it.name != "jsconfig.json" }
        .forEach { file ->
            val path = file.relativeTo(dir).invariantSeparatorsPath
            when {
                SECRET_FILE.matches(file.name) -> skippedSecrets += path
                !ModuleManifests.isRelativePath(path) ->
                    throw UsageError("unsupported file name: $path (allowed: letters, digits and _ . @ + -)")
                else -> files[path] = file.readBytes()
            }
        }
    return files
}

/** @param target where the package goes instead of the file named by `--out` or derived from the manifest. */
internal fun pack(dir: File, options: Options, target: File? = null): String {
    // A single script packs as it is: its manifest comes from its code and the comment header on top.
    val script = dir.takeIf { it.isFile && it.extension != ModulePackageFormat.EXTENSION }
    val manifestFile = File(dir, ModulePackageFormat.MANIFEST)
    if (script == null && !manifestFile.isFile) throw UsageError("$manifestFile not found; create a project with 'mfrg new'")
    val manifest: ModuleManifest
    val manifestJson: String
    if (script != null) {
        manifest = scriptManifest(script, script.readText())
        manifestJson = ModuleManifests.encode(manifest)
    } else {
        manifestJson = manifestFile.readText()
        manifest = when (val parsed = ModuleManifests.parse(manifestJson)) {
            is ManifestResult.Valid -> parsed.manifest
            is ManifestResult.Invalid -> throw UsageError("invalid manifest: ${parsed.problems.joinToString("; ")}")
        }
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
    val output = (target ?: File(options.optional("out") ?: "${manifest.id}-${manifest.version}.${ModulePackageFormat.EXTENSION}")).absoluteFile

    val skippedSecrets = mutableListOf<String>()
    val files = (if (script != null) mapOf(manifest.entry to script.readBytes()) else projectFiles(dir, skippedSecrets, exclude = output))
        .mapKeysTo(sortedMapOf()) { ModulePackageFormat.CODE_PREFIX + it.key }
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

private fun link(file: File, address: String): String = when (val check = ModulePackageVerifier.verify(file)) {
    is PackageCheck.Invalid -> throw UsageError("invalid package: ${check.reason}")
    is PackageCheck.Valid -> try {
        InstallLinks.create(address, check.signer)
    } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "invalid address")
    }
}

private fun verify(file: File): String = when (val check = ModulePackageVerifier.verify(file)) {
    is PackageCheck.Invalid -> throw UsageError("invalid package: ${check.reason}")
    is PackageCheck.Valid -> with(check.manifest) {
        "$name $version ($id)\nRuntime: ${runtime.name.lowercase()}, entry: $entry\n" +
            "Permissions: ${permissions.joinToString().ifEmpty { "none" }}\nSigner: ${check.signer}"
    }
}
