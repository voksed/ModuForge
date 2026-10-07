package dev.moduforge.script

import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.UiEvent
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrappedException
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import kotlin.concurrent.thread

/**
 * Runs a JavaScript program as a module, with the same lifecycle as a Lua script: the entry
 * script starts on its own thread at `onStart` and runs until it ends or the module stops.
 *
 * The host API is the global object `mf`, a binding over [ScriptHost]; `docs/en/js-api.md`
 * is its reference. Calls block; a failure the script is expected to handle is thrown as an
 * `Error`. `require` loads other files of the package (CommonJS) and the libraries shipped
 * with the runtime (`require("mf/telegram")`). There is no access to Java classes, no timers
 * and no `async`: the language level is ES5 with the ES6 features of Rhino 1.7.
 */
internal class JsScriptModule(private val files: Map<String, ByteArray>, private val entry: String) : Module {

    private var main: Thread? = null

    @Volatile
    private var host: ScriptHost? = null

    override suspend fun onStart(context: ModuleContext) {
        val source = files[entry] ?: error("entry script $entry is missing")
        val scriptHost = ScriptHost(context).also { host = it }
        main = thread(name = "js-main", isDaemon = true) {
            val cx = Context.enter()
            try {
                // Interpreted mode: generating classes is neither possible on Android nor needed.
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.setClassShutter { false }
                val scope = cx.initSafeStandardObjects()
                JsBinding(cx, scope, scriptHost, files).install()
                cx.evaluateString(scope, source.decodeToString(), entry, 1, null)
                context.log.info("script finished")
                context.stopSelf("script finished")
            } catch (e: RhinoException) {
                if (e.isInterruption()) return@thread
                val message = "${e.details()} (${e.sourceName()}:${e.lineNumber()})"
                context.log.error("script failed: $message")
                context.stopSelf("script failed: $message")
            } finally {
                Context.exit()
            }
        }
    }

    override suspend fun onUiEvent(context: ModuleContext, event: UiEvent) {
        host?.uiEvents?.put(event)
    }

    override suspend fun onStop(context: ModuleContext) {
        main?.interrupt()
        main = null
    }

    private fun Throwable.isInterruption(): Boolean =
        generateSequence(this) { it.cause }.any { it is InterruptedException || (it is WrappedException && it.wrappedException is InterruptedException) }
}

/** Builds the `mf`, `console` and `require` globals of one JavaScript context. */
private class JsBinding(
    private val cx: Context,
    private val scope: ScriptableObject,
    private val host: ScriptHost,
    private val files: Map<String, ByteArray>,
) {
    private val modules = mutableMapOf<String, Any?>()

    fun install() {
        val manifest = host.context.manifest
        val log = fn { args -> host.log(args.joinToString(" ") { text(it) }); Undefined.instance }
        val algorithms = listOf("md5", "sha1", "sha256", "sha512")
        // A trailing true returns bytes (Uint8Array) instead of hexadecimal text.
        fun digest(data: ByteArray, raw: Any?): Any = if (raw == true) bytesOut(data) else host.hexEncode(data)

        val mf = obj(
            "id" to manifest.id,
            "name" to manifest.name,
            "version" to manifest.version,
            "log" to log,
            "sleep" to fn { args -> host.sleep(number(args, 0)); Undefined.instance },
            "time" to fn { host.time() },
            "date" to fn { args -> host.date(optText(args, 0), optNumber(args, 1)) },
            "dateFields" to fn { args -> toJs(host.dateFields(optNumber(args, 0), args.getOrNull(1) == true)) },
            "request" to fn { args -> host.request(text(args, 0), optText(args, 1).orEmpty(), optText(args, 2)).first },
            "granted" to fn { args -> host.granted(text(args, 0), optText(args, 1)) },
            "http" to fn { args -> http(args.getOrNull(0)) },
            "connect" to fn { args ->
                val options = toGeneric(args.getOrNull(2)) as? Map<*, *>
                socket(host.connect(text(args, 0), number(args, 1).toInt(), options?.get("tls") == true))
            },
            "websocket" to fn { args -> websocket(host.websocket(text(args, 0), stringMap(toGeneric(args.getOrNull(1))))) },
            "storage" to obj(
                "read" to fn { args -> host.storageRead(text(args, 0))?.decodeToString() },
                "readBytes" to fn { args -> host.storageRead(text(args, 0))?.let(::bytesOut) },
                "write" to fn { args -> host.storageWrite(text(args, 0), bytesIn(args.getOrNull(1))); true },
                "delete" to fn { args -> host.storageDelete(text(args, 0)) },
                "list" to fn { toJs(host.storageList()) },
            ),
            "notify" to fn { args -> host.notify(text(args, 0), optText(args, 1).orEmpty()); true },
            "ask" to fn { args -> host.ask(text(args, 0), args.getOrNull(1) == true) },
            "urlencode" to fn { args -> host.urlencode(text(args, 0)) },
            "hash" to obj(*algorithms.map { name ->
                name to fn { args -> digest(host.hash(name, bytesIn(args.getOrNull(0))), args.getOrNull(1)) }
            }.toTypedArray()),
            "hmac" to obj(*algorithms.map { name ->
                name to fn { args -> digest(host.hmac(name, bytesIn(args.getOrNull(0)), bytesIn(args.getOrNull(1))), args.getOrNull(2)) }
            }.toTypedArray()),
            "base64" to obj(
                "encode" to fn { args -> host.base64Encode(bytesIn(args.getOrNull(0)), args.getOrNull(1) == true) },
                "decode" to fn { args -> host.base64Decode(text(args, 0)).decodeToString() },
                "decodeBytes" to fn { args -> bytesOut(host.base64Decode(text(args, 0))) },
            ),
            "hex" to obj(
                "encode" to fn { args -> host.hexEncode(bytesIn(args.getOrNull(0))) },
                "decode" to fn { args -> bytesOut(host.hexDecode(text(args, 0))) },
            ),
            "random" to fn { args -> bytesOut(host.randomBytes(number(args, 0).toInt())) },
            "ui" to obj(
                "show" to fn { args -> host.uiShow(toGeneric(args.getOrNull(0))); Undefined.instance },
                "clear" to fn { host.uiShow(null); Undefined.instance },
                "wait" to fn { args -> toJs(host.uiNext(optNumber(args, 0))) },
            ),
        )
        DeviceApi.services.forEach { (service, calls) ->
            ScriptableObject.putProperty(mf, service, obj(*calls.map { call ->
                call.name to fn { args ->
                    val first = args.firstOrNull()
                    val named: Map<String, Any?> = if (args.size == 1 && call.params.isNotEmpty() && first is Scriptable && first !is NativeArray && first !is Function) {
                        (toGeneric(first) as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value }
                    } else {
                        DeviceApi.named(call, args.map { toGeneric(it) })
                    }
                    toJs(host.device(service, call.name, named))
                }
            }.toTypedArray()))
        }
        ScriptableObject.putProperty(scope, "mf", mf)
        ScriptableObject.putProperty(scope, "console", obj("log" to log, "info" to log, "warn" to log, "error" to log))
        ScriptableObject.putProperty(scope, "require", require(baseDir = ""))
    }

    private fun http(request: Any?): Any {
        val options = toGeneric(request) as? Map<*, *> ?: throw IllegalArgumentException("mf.http needs an object with a url")
        val uploads = (options["files"] as? List<*>).orEmpty().map { item ->
            val file = item as? Map<*, *> ?: throw IllegalArgumentException("each file must be an object")
            Upload(
                field = file["field"]?.toString() ?: "file",
                filename = file["filename"]?.toString() ?: "file",
                contentType = file["type"]?.toString() ?: "application/octet-stream",
                content = genericBytes(file["content"]),
            )
        }
        val response = host.http(
            HttpCall(
                url = options["url"]?.toString() ?: throw IllegalArgumentException("mf.http needs a url"),
                method = options["method"]?.toString() ?: "GET",
                headers = stringMap(options["headers"]),
                body = options["body"]?.let(::genericBytes),
                form = stringMap(options["form"]),
                files = uploads,
                redirects = (options["redirects"] as? Number)?.toInt() ?: HttpCall.DEFAULT_REDIRECTS,
            ),
        )
        val body = response.body.decodeToString()
        return obj(
            "status" to response.status,
            "body" to body,
            "headers" to toJs(response.headers),
            "url" to response.url,
            "bytes" to fn { bytesOut(response.body) },
            "json" to fn { parseJson(body) },
        )
    }

    private fun socket(socket: ScriptSocket): Any = obj(
        "read" to fn { args -> socket.read(optNumber(args, 0)?.toInt() ?: (16 * 1024), optNumber(args, 1))?.decodeToString() },
        "readBytes" to fn { args -> socket.read(optNumber(args, 0)?.toInt() ?: (16 * 1024), optNumber(args, 1))?.let(::bytesOut) },
        "readExactly" to fn { args -> socket.readExactly(number(args, 0).toInt(), optNumber(args, 1))?.let(::bytesOut) },
        "readLine" to fn { args -> socket.readLine(optNumber(args, 0)) },
        "write" to fn { args -> socket.write(bytesIn(args.getOrNull(0))); true },
        "close" to fn { socket.close(); Undefined.instance },
    )

    private fun websocket(socket: ScriptWebSocket): Any = obj(
        "send" to fn { args ->
            val data = args.getOrNull(0)
            if (data is CharSequence) socket.sendText(data.toString()) else socket.sendBinary(bytesIn(data))
            true
        },
        "ping" to fn { socket.ping(); true },
        "receive" to fn { args ->
            socket.receive(optNumber(args, 0))?.let { if (it.text) it.data.decodeToString() else bytesOut(it.data) }
        },
        "close" to fn { socket.close(); Undefined.instance },
    )

    /** CommonJS `require` resolving against [baseDir]: package files first, then the bundled `mf/` libraries. */
    private fun require(baseDir: String): BaseFunction = fn { args ->
        val name = text(args, 0)
        val path = resolve(baseDir, name) ?: throw IllegalArgumentException("module '$name' not found")
        modules.getOrPut(path) {
            val source = files[path]?.decodeToString() ?: builtin(path)!!
            val module = obj("exports" to cx.newObject(scope))
            // Registered before it runs, so that circular requires see the partial exports.
            modules[path] = ScriptableObject.getProperty(module, "exports")
            val wrapper = cx.evaluateString(scope, "(function (exports, require, module) {$source\n})", path, 1, null) as Function
            val exports = ScriptableObject.getProperty(module, "exports")
            wrapper.call(cx, scope, scope, arrayOf(exports, require(path.substringBeforeLast('/', "")), module))
            ScriptableObject.getProperty(module, "exports")
        }
    }

    private fun resolve(baseDir: String, name: String): String? {
        val joined = if (name.startsWith("./") || name.startsWith("../")) "$baseDir/$name" else name
        val parts = mutableListOf<String>()
        joined.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        val path = parts.joinToString("/")
        return listOf(path, "$path.js", "$path/index.js").firstOrNull { it in files || builtin(it) != null }
    }

    private fun builtin(path: String): String? =
        if (BUILTIN_PATH.matches(path)) JsBinding::class.java.getResourceAsStream("/js/$path")?.use { it.readBytes().decodeToString() } else null

    private fun parseJson(text: String): Any? {
        val json = ScriptableObject.getProperty(scope, "JSON") as Scriptable
        return (ScriptableObject.getProperty(json, "parse") as Function).call(cx, scope, json, arrayOf(text))
    }

    // --- conversions ------------------------------------------------------------------------

    /**
     * Wraps a host call as a JavaScript function. An expected failure is thrown as `Error`,
     * a bad argument as `TypeError`; a module stop propagates and ends the script.
     */
    private fun fn(body: (Array<Any?>) -> Any?): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? = try {
            body(args)
        } catch (e: HostFailure) {
            throw ScriptRuntime.constructError("Error", e.message)
        } catch (e: IllegalArgumentException) {
            throw ScriptRuntime.constructError("TypeError", e.message)
        }
    }

    private fun obj(vararg entries: Pair<String, Any?>): Scriptable =
        cx.newObject(scope).also { target -> entries.forEach { (key, value) -> ScriptableObject.putProperty(target, key, value) } }

    private fun toJs(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> cx.newObject(scope).also { target ->
            value.forEach { (key, item) -> ScriptableObject.putProperty(target, key.toString(), toJs(item)) }
        }
        is List<*> -> cx.newArray(scope, value.map(::toJs).toTypedArray())
        is ByteArray -> bytesOut(value)
        else -> value
    }

    /** Plain Kotlin value for a JavaScript one: maps, lists, strings, doubles, booleans, byte arrays. */
    private fun toGeneric(value: Any?, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("value is nested too deeply or refers to itself")
        return when (value) {
            null, is Undefined, Scriptable.NOT_FOUND -> null
            is CharSequence -> value.toString()
            is Boolean -> value
            is Number -> value.toDouble()
            is NativeTypedArrayView<*> -> bytesIn(value)
            is NativeArray -> (0 until value.length.toInt()).map { toGeneric(value.get(it, value), depth + 1) }
            is Function -> throw IllegalArgumentException("a function cannot be passed to the host")
            is Scriptable -> value.ids.associate { id -> id.toString() to toGeneric(ScriptableObject.getProperty(value, id.toString()), depth + 1) }
            else -> value.toString()
        }
    }

    private fun stringMap(value: Any?): Map<String, String> =
        (toGenericIfNeeded(value) as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() }

    private fun toGenericIfNeeded(value: Any?): Any? = if (value is Map<*, *> && value !is Scriptable) value else toGeneric(value)

    /** Bytes of a script value: a string is taken as UTF-8, a typed array or an array of numbers as raw bytes. */
    private fun bytesIn(value: Any?): ByteArray = when (value) {
        is CharSequence -> value.toString().toByteArray()
        is NativeTypedArrayView<*> -> ByteArray(value.arrayLength) { (ScriptRuntime.toNumber(value.get(it, value))).toInt().toByte() }
        is NativeArray -> ByteArray(value.length.toInt()) { ScriptRuntime.toNumber(value.get(it, value)).toInt().toByte() }
        else -> throw IllegalArgumentException("expected a string or bytes")
    }

    private fun genericBytes(value: Any?): ByteArray = when (value) {
        is String -> value.toByteArray()
        is ByteArray -> value
        is List<*> -> ByteArray(value.size) { (value[it] as Number).toInt().toByte() }
        else -> throw IllegalArgumentException("expected a string or bytes")
    }

    private fun bytesOut(data: ByteArray): Any {
        val array = cx.newObject(scope, "Uint8Array", arrayOf<Any>(data.size))
        data.forEachIndexed { index, byte -> array.put(index, array, byte.toInt() and 0xFF) }
        return array
    }

    private fun text(value: Any?): String = if (value == null || value is Undefined) "undefined" else Context.toString(value)

    private fun text(args: Array<Any?>, index: Int): String =
        optText(args, index) ?: throw IllegalArgumentException("argument ${index + 1} must be a string")

    private fun optText(args: Array<Any?>, index: Int): String? =
        args.getOrNull(index)?.takeUnless { it is Undefined }?.let { Context.toString(it) }

    private fun number(args: Array<Any?>, index: Int): Double =
        optNumber(args, index) ?: throw IllegalArgumentException("argument ${index + 1} must be a number")

    private fun optNumber(args: Array<Any?>, index: Int): Double? =
        (args.getOrNull(index) as? Number)?.toDouble()

    private companion object {
        const val MAX_DEPTH = 64
        val BUILTIN_PATH = Regex("""mf/[a-z_]+\.js""")
    }
}
