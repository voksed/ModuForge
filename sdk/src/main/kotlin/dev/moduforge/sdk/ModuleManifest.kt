package dev.moduforge.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
public enum class UiKind {
    @SerialName("compose")
    COMPOSE,

    @SerialName("none")
    NONE,

    @SerialName("webview")
    WEBVIEW,
}

/** What executes the module code. */
@Serializable
public enum class ModuleRuntimeKind {
    /** Compiled classes (`code/classes*.dex`); [ModuleManifest.entry] names a [Module] implementation. */
    @SerialName("dex")
    DEX,

    /** Lua sources; [ModuleManifest.entry] is the path of the main script inside `code/`. */
    @SerialName("lua")
    LUA,

    /** JavaScript sources; [ModuleManifest.entry] is the path of the main script inside `code/`. */
    @SerialName("js")
    JS,

    /** Python sources; [ModuleManifest.entry] is the path of the main script inside `code/`. */
    @SerialName("python")
    PYTHON,
}

/**
 * Module descriptor shipped with every module.
 *
 * @property id reverse-DNS identifier, unique per host.
 * @property version semantic version of the module.
 * @property sdkRange SDK versions the module was built for, e.g. `>=1.0.0 <2.0.0`.
 * @property entry class name or script path, depending on [runtime].
 * @property permissions capabilities the module may ask for; anything else is refused without a prompt.
 * @property permissionReasons the author's explanation of each permission, shown before installation.
 * Untrusted text; keys must be listed in [permissions].
 * @property description what the module does, shown before installation. Untrusted text.
 */
@Serializable
public data class ModuleManifest(
    val id: String,
    val name: String,
    val version: String,
    val sdkRange: String,
    val entry: String,
    val runtime: ModuleRuntimeKind = ModuleRuntimeKind.DEX,
    val permissions: List<Capability> = emptyList(),
    val permissionReasons: Map<Capability, String> = emptyMap(),
    val ui: UiKind = UiKind.NONE,
    val author: String = "",
    val description: String = "",
)

public sealed interface ManifestResult {
    public data class Valid(val manifest: ModuleManifest) : ManifestResult

    public data class Invalid(val problems: List<String>) : ManifestResult
}

/** Parsing and structural validation of module manifests. */
public object ModuleManifests {
    private const val MAX_ID_LENGTH = 128
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_REASON_LENGTH = 300
    private val PATH = Regex("""^[A-Za-z0-9_.@+-]+(/[A-Za-z0-9_.@+-]+)*$""")

    private val ID = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
    private val CLASS_NAME = Regex("""^([A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$""")

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Decodes and validates a manifest. Unknown keys and unknown capabilities are rejected. */
    public fun parse(text: String): ManifestResult {
        val manifest = try {
            json.decodeFromString(ModuleManifest.serializer(), text)
        } catch (e: SerializationException) {
            return ManifestResult.Invalid(listOf("Malformed manifest: ${e.message?.lineSequence()?.firstOrNull()}"))
        }
        val problems = validate(manifest)
        return if (problems.isEmpty()) ManifestResult.Valid(manifest) else ManifestResult.Invalid(problems)
    }

    public fun encode(manifest: ModuleManifest): String =
        json.encodeToString(ModuleManifest.serializer(), manifest)

    /** Returns the structural problems of [manifest]; an empty list means it is well-formed. */
    public fun validate(manifest: ModuleManifest): List<String> = buildList {
        if (manifest.id.length > MAX_ID_LENGTH || !ID.matches(manifest.id)) {
            add("id must be a lowercase reverse-DNS name of at most $MAX_ID_LENGTH characters")
        }
        if (manifest.name.isBlank() || manifest.name.length > MAX_NAME_LENGTH) {
            add("name must be 1..$MAX_NAME_LENGTH characters")
        }
        if (SemVer.parseOrNull(manifest.version) == null) {
            add("version is not a semantic version: '${manifest.version}'")
        }
        if (SemVerRange.parseOrNull(manifest.sdkRange) == null) {
            add("sdkRange is not a version range: '${manifest.sdkRange}'")
        }
        if (manifest.runtime == ModuleRuntimeKind.DEX) {
            if (!CLASS_NAME.matches(manifest.entry)) add("entry must be a fully qualified class name")
        } else if (!isRelativePath(manifest.entry)) {
            add("entry must be a relative path of a script inside code/")
        }
        if (manifest.permissions.size != manifest.permissions.toSet().size) {
            add("permissions contains duplicates")
        }
        if (!manifest.permissions.containsAll(manifest.permissionReasons.keys)) {
            add("permissionReasons explains permissions that are not declared")
        }
        if (manifest.description.length > MAX_DESCRIPTION_LENGTH ||
            manifest.permissionReasons.values.any { it.length > MAX_REASON_LENGTH }
        ) {
            add("description is limited to $MAX_DESCRIPTION_LENGTH characters, a permission reason to $MAX_REASON_LENGTH")
        }
    }

    /** True for a non-empty `/`-separated path that stays inside its root. */
    public fun isRelativePath(path: String): Boolean =
        PATH.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** True when the module declares support for [sdkVersion]. */
    public fun supportsSdk(manifest: ModuleManifest, sdkVersion: SemVer = ModuForgeSdk.version): Boolean =
        SemVerRange.parseOrNull(manifest.sdkRange)?.contains(sdkVersion) == true
}
