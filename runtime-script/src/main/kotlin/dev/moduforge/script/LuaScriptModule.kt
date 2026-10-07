package dev.moduforge.script

import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import dev.moduforge.sdk.ui.UiEvent
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.ResourceFinder
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Runs a Lua program as a module. The entry script is the program: it starts on its own
 * thread at `onStart` and may run for as long as the module does; stopping the module
 * destroys the process. Scripts and `require`d files come from the package, held in memory.
 *
 * The host API is the global table `mf`, a binding over [ScriptHost]; `docs/en/lua-api.md`
 * is its reference. Calls that can fail for outside reasons return `nil, message`.
 * Of the standard libraries `io` and `luajava` are absent and `os` offers only
 * `time`, `date` and `clock`.
 */
internal class LuaScriptModule(private val files: Map<String, ByteArray>, private val entry: String) : Module {

    private var main: Thread? = null

    @Volatile
    private var host: ScriptHost? = null

    override suspend fun onStart(context: ModuleContext) {
        val source = files[entry] ?: error("entry script $entry is missing")
        val scriptHost = ScriptHost(context).also { host = it }
        main = thread(name = "lua-main", isDaemon = true) {
            try {
                globals(scriptHost).load(source.decodeToString(), "@$entry").call()
                context.log.info("script finished")
                context.stopSelf("script finished")
            } catch (e: LuaError) {
                if (e.cause is InterruptedException) return@thread
                context.log.error("script failed: ${e.message}")
                context.stopSelf("script failed: ${e.message}")
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

    private fun globals(host: ScriptHost): Globals {
        val globals = Globals()
        globals.load(BaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(JseMathLib())
        LoadState.install(globals)
        LuaC.install(globals)
        globals.finder = ResourceFinder { name ->
            // LuaJ builds the path with the platform separator.
            val path = name.replace('\\', '/').removePrefix("./")
            files[path]?.inputStream() ?: builtin(path)
        }
        globals.get("package").set("path", "?.lua;?/init.lua")

        val manifest = host.context.manifest
        val mf = LuaTable()
        mf.set("id", manifest.id)
        mf.set("name", manifest.name)
        mf.set("version", manifest.version)

        val log = fn { args ->
            host.log((1..args.narg()).joinToString("\t") { args.arg(it).tojstring() })
            LuaValue.NONE
        }
        mf.set("log", log)
        globals.set("print", log)
        mf.set("sleep", fn { args -> host.sleep(args.checkdouble(1)); LuaValue.NONE })
        mf.set("time", fn { LuaValue.valueOf(host.time()) })
        val date = fn { args -> date(host, args.optjstring(1, null), if (args.isnoneornil(2)) null else args.checkdouble(2)) }
        mf.set("date", date)

        mf.set("request", fn { args ->
            val (granted, reason) = host.request(args.checkjstring(1), args.optjstring(2, ""), args.optjstring(3, null))
            if (granted) LuaValue.TRUE else LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(reason.orEmpty()))
        })
        mf.set("granted", fn { args -> LuaValue.valueOf(host.granted(args.checkjstring(1), args.optjstring(2, null))) })

        mf.set("http", fn { args -> http(host, args.checktable(1)) })
        mf.set("connect", fn { args ->
            val options = args.opttable(3, LuaTable())
            socket(host.connect(args.checkjstring(1), args.checkint(2), options.get("tls").optboolean(false)))
        })
        mf.set("websocket", fn { args -> websocket(host.websocket(args.checkjstring(1), stringMap(args.opttable(2, LuaTable())))) })

        mf.set("storage", table(
            "read" to fn { args -> host.storageRead(args.checkjstring(1))?.let(LuaString::valueUsing) ?: LuaValue.NIL },
            "write" to fn { args -> host.storageWrite(args.checkjstring(1), bytes(args.checkstring(2))); LuaValue.TRUE },
            "delete" to fn { args -> LuaValue.valueOf(host.storageDelete(args.checkjstring(1))) },
            "list" to fn { LuaValues.fromGeneric(host.storageList()) },
        ))
        mf.set("notify", fn { args -> host.notify(args.checkjstring(1), args.optjstring(2, "")); LuaValue.TRUE })
        mf.set("ask", fn { args -> host.ask(args.checkjstring(1), args.optboolean(2, false))?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL })

        mf.set("json", table(
            "decode" to fn { args -> LuaJson.decode(args.checkjstring(1)) },
            "encode" to fn { args -> LuaValue.valueOf(LuaJson.encode(args.arg1())) },
        ))
        mf.set("urlencode", fn { args -> LuaValue.valueOf(host.urlencode(args.checkjstring(1))) })

        val algorithms = listOf("md5", "sha1", "sha256", "sha512")
        // A trailing true returns raw bytes instead of hexadecimal text.
        fun digest(data: ByteArray, raw: Boolean): LuaValue = if (raw) LuaString.valueUsing(data) else LuaValue.valueOf(host.hexEncode(data))
        mf.set("hash", table(*algorithms.map { name ->
            name to fn { args -> digest(host.hash(name, bytes(args.checkstring(1))), args.optboolean(2, false)) }
        }.toTypedArray()))
        mf.set("hmac", table(*algorithms.map { name ->
            name to fn { args -> digest(host.hmac(name, bytes(args.checkstring(1)), bytes(args.checkstring(2))), args.optboolean(3, false)) }
        }.toTypedArray()))
        mf.set("base64", table(
            "encode" to fn { args -> LuaValue.valueOf(host.base64Encode(bytes(args.checkstring(1)), args.optboolean(2, false))) },
            "decode" to fn { args -> LuaString.valueUsing(host.base64Decode(args.checkjstring(1))) },
        ))
        mf.set("hex", table(
            "encode" to fn { args -> LuaValue.valueOf(host.hexEncode(bytes(args.checkstring(1)))) },
            "decode" to fn { args -> LuaString.valueUsing(host.hexDecode(args.checkjstring(1))) },
        ))
        mf.set("random", fn { args -> LuaString.valueUsing(host.randomBytes(args.checkint(1))) })

        mf.set("ui", table(
            "show" to fn { args -> host.uiShow(LuaValues.toGeneric(args.arg1())); LuaValue.NONE },
            "clear" to fn { host.uiShow(null); LuaValue.NONE },
            "wait" to fn { args ->
                host.uiNext(if (args.isnoneornil(1)) null else args.checkdouble(1))?.let(LuaValues::fromGeneric) ?: LuaValue.NIL
            },
        ))

        DeviceApi.services.forEach { (service, calls) ->
            mf.set(service, table(*calls.map { call ->
                call.name to fn { args ->
                    val first = args.arg1()
                    val named = if (args.narg() == 1 && first.istable() && call.params.isNotEmpty()) {
                        LuaValues.toGeneric(first) as? Map<*, *> ?: emptyMap<String, Any?>()
                    } else {
                        DeviceApi.named(call, (1..args.narg()).map { LuaValues.toGeneric(args.arg(it)) })
                    }
                    LuaValues.fromGeneric(host.device(service, call.name, named.entries.associate { it.key.toString() to it.value }))
                }
            }.toTypedArray()))
        }

        globals.set("mf", mf)
        globals.set("os", table(
            "time" to fn { LuaValue.valueOf(host.time().toLong().toDouble()) },
            "date" to date,
            "clock" to fn { LuaValue.valueOf(System.nanoTime() / 1e9) },
        ))
        return globals
    }

    /** `mf.date`: formatted text, or a table of fields for the `*t` / `!*t` formats. */
    private fun date(host: ScriptHost, format: String?, time: Double?): LuaValue = when (format) {
        "*t", "!*t" -> LuaValues.fromGeneric(host.dateFields(time, utc = format.startsWith("!")))
        else -> LuaValue.valueOf(host.date(format, time))
    }

    private fun http(host: ScriptHost, request: LuaTable): LuaValue {
        val files = request.get("files").opttable(LuaTable())
        val uploads = files.keys().map { key ->
            val file = files.get(key).checktable()
            Upload(
                field = file.get("field").optjstring(if (key.isint()) "file" else key.tojstring()),
                filename = file.get("filename").optjstring("file"),
                contentType = file.get("type").optjstring("application/octet-stream"),
                content = bytes(file.get("content").checkstring()),
            )
        }
        val response = host.http(
            HttpCall(
                url = request.get("url").checkjstring(),
                method = request.get("method").optjstring("GET"),
                headers = stringMap(request.get("headers").opttable(LuaTable())),
                body = request.get("body").takeUnless { it.isnil() }?.checkstring()?.let(::bytes),
                form = stringMap(request.get("form").opttable(LuaTable())),
                files = uploads,
                redirects = request.get("redirects").optint(HttpCall.DEFAULT_REDIRECTS),
            ),
        )
        return table(
            "status" to LuaValue.valueOf(response.status),
            "body" to LuaString.valueUsing(response.body),
            "headers" to LuaValues.fromGeneric(response.headers),
            "url" to LuaValue.valueOf(response.url),
        )
    }

    /** Lua object for an open connection; methods take the object as their first argument. */
    private fun socket(socket: ScriptSocket): LuaValue {
        fun timeout(args: Varargs, index: Int): Double? = if (args.isnoneornil(index)) null else args.checkdouble(index)
        fun data(bytes: ByteArray?): Varargs =
            bytes?.let(LuaString::valueUsing) ?: LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("closed"))
        return table(
            "read" to fn { args -> data(socket.read(args.optint(2, 16 * 1024), timeout(args, 3))) },
            "read_exactly" to fn { args -> data(socket.readExactly(args.checkint(2), timeout(args, 3))) },
            "read_line" to fn { args ->
                socket.readLine(timeout(args, 2))?.let { LuaValue.valueOf(it) } ?: LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("closed"))
            },
            "write" to fn { args -> socket.write(bytes(args.checkstring(2))); LuaValue.TRUE },
            "close" to fn { socket.close(); LuaValue.NONE },
        )
    }

    private fun websocket(socket: ScriptWebSocket): LuaValue = table(
        "send" to fn { args -> socket.sendText(args.checkjstring(2)); LuaValue.TRUE },
        "send_binary" to fn { args -> socket.sendBinary(bytes(args.checkstring(2))); LuaValue.TRUE },
        "ping" to fn { socket.ping(); LuaValue.TRUE },
        "receive" to fn { args ->
            val message = socket.receive(if (args.isnoneornil(2)) null else args.checkdouble(2))
            if (message == null) {
                LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("closed"))
            } else {
                LuaValue.varargsOf(LuaString.valueUsing(message.data), LuaValue.valueOf(message.text))
            }
        },
        "close" to fn { socket.close(); LuaValue.NONE },
    )

    /**
     * Wraps a host call as a Lua function. Expected failures become `nil, message`, a module
     * stop unwinds the script, and a bad argument is an ordinary Lua error.
     */
    private fun fn(body: (Varargs) -> Varargs): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = try {
            body(args)
        } catch (e: HostFailure) {
            LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.message))
        } catch (e: InterruptedException) {
            throw LuaError(e)
        } catch (e: IllegalArgumentException) {
            throw LuaError(e.message)
        }
    }

    private fun table(vararg entries: Pair<String, LuaValue>): LuaTable =
        LuaTable().also { table -> entries.forEach { (key, value) -> table.set(key, value) } }

    private fun stringMap(table: LuaTable): Map<String, String> =
        table.keys().associate { it.tojstring() to table.get(it).tojstring() }

    private fun bytes(string: LuaString): ByteArray = ByteArray(string.m_length).also { string.copyInto(0, it, 0, string.m_length) }

    /** Libraries shipped with the runtime, available as `require("mf.<name>")` unless the package has its own. */
    private fun builtin(path: String): InputStream? =
        if (BUILTIN_PATH.matches(path)) LuaScriptModule::class.java.getResourceAsStream("/lua/$path") else null

    private companion object {
        val BUILTIN_PATH = Regex("""mf/[a-z_]+\.lua""")
    }
}

/** Conversion between Lua values and plain Kotlin values (maps, lists, strings, numbers, booleans). */
internal object LuaValues {
    private const val MAX_DEPTH = 64

    fun toGeneric(value: LuaValue, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) throw LuaError("value is nested too deeply or refers to itself")
        return when {
            value.isnil() -> null
            value.isboolean() -> value.toboolean()
            value.type() == LuaValue.TNUMBER -> value.todouble()
            value.isstring() -> value.tojstring()
            value.istable() -> {
                val table = value.checktable()
                val keys = table.keys()
                val length = table.length()
                if (keys.isNotEmpty() && keys.size == length && keys.all { it.isint() }) {
                    (1..length).map { toGeneric(table.get(it), depth + 1) }
                } else {
                    keys.associate { it.tojstring() to toGeneric(table.get(it), depth + 1) }
                }
            }
            else -> throw LuaError("${value.typename()} cannot be passed to the host")
        }
    }

    fun fromGeneric(value: Any?): LuaValue = when (value) {
        null -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(value)
        is Int -> LuaValue.valueOf(value)
        is Number -> LuaValue.valueOf(value.toDouble())
        is String -> LuaValue.valueOf(value)
        is ByteArray -> LuaString.valueUsing(value)
        is List<*> -> LuaTable().also { table -> value.forEachIndexed { index, item -> table.set(index + 1, fromGeneric(item)) } }
        is Map<*, *> -> LuaTable().also { table -> value.forEach { (key, item) -> table.set(key.toString(), fromGeneric(item)) } }
        else -> LuaValue.valueOf(value.toString())
    }
}
