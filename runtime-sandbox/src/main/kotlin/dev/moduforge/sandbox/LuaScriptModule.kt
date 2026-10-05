package dev.moduforge.sandbox

import dev.moduforge.sdk.Capability
import dev.moduforge.sdk.CapabilityRequest
import dev.moduforge.sdk.CapabilityResult
import dev.moduforge.sdk.Module
import dev.moduforge.sdk.ModuleContext
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import dev.moduforge.sdk.CapabilityNotGrantedException
import java.io.IOException
import java.io.InputStream
import org.luaj.vm2.lib.ZeroArgFunction
import java.net.URLEncoder
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.ResourceFinder
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import kotlin.concurrent.thread

/**
 * Runs a Lua program as a module. The entry script is the program: it starts on its own
 * thread at `onStart` and may run for as long as the module does; stopping the module
 * destroys the process. Scripts and `require`d files come from the package, held in memory.
 *
 * Host API available to scripts as the global table `mf`:
 * - `mf.log(text)`; `print` is routed to it as well
 * - `mf.request(capability, reason [, target])` → `granted, denialReason`; blocks while the user decides
 * - `mf.granted(capability [, target])` → boolean
 * - `mf.sleep(seconds)`, `mf.time()` (seconds since the Unix epoch)
 * - `mf.http{url=, method=, headers=, body=}` → `{status, body, headers}`; needs NETWORK_OUTBOUND
 * - `mf.storage.read(path)`, `.write(path, data)`, `.delete(path)`, `.list()`; needs FILE_SANDBOXED
 *
 * - `mf.notify(title [, text])`; needs NOTIFICATIONS
 * - `mf.ask(question [, secret])` → the user's answer, or `nil` when dismissed; blocks until answered
 * - `mf.json.decode(text)`, `mf.json.encode(value)`, `mf.urlencode(text)`
 *
 * Network, storage and notification calls return `nil, message` on failure instead of raising an error.
 * - `mf.id`, `mf.name`, `mf.version`
 *
 * The standard `os`, `io` and `luajava` libraries are not available.
 */
internal class LuaScriptModule(private val files: Map<String, ByteArray>, private val entry: String) : Module {

    private var main: Thread? = null

    override suspend fun onStart(context: ModuleContext) {
        val source = files[entry] ?: error("entry script $entry is missing")
        main = thread(name = "lua-main", isDaemon = true) {
            try {
                globals(context).load(source.decodeToString(), "@$entry").call()
                context.log.info("script finished")
                context.stopSelf("script finished")
            } catch (e: LuaError) {
                if (e.cause is InterruptedException) return@thread
                context.log.error("script failed: ${e.message}")
                context.stopSelf("script failed: ${e.message}")
            }
        }
    }

    override suspend fun onStop(context: ModuleContext) {
        main?.interrupt()
        main = null
    }

    private fun globals(context: ModuleContext): Globals {
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

        val log = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                context.log.info((1..args.narg()).joinToString("\t") { args.arg(it).tojstring() })
                return LuaValue.NONE
            }
        }
        val api = LuaTable()
        api.set("id", context.manifest.id)
        api.set("name", context.manifest.name)
        api.set("version", context.manifest.version)
        api.set("log", log)
        api.set("request", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val capability = capability(args.checkjstring(1))
                    ?: return LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf("UNKNOWN_CAPABILITY"))
                val request = CapabilityRequest(capability, args.optjstring(2, ""), args.optjstring(3, null))
                return when (val result = interruptible { runBlocking { context.capabilities.request(request) } }) {
                    CapabilityResult.Granted -> LuaValue.TRUE
                    is CapabilityResult.Denied -> LuaValue.varargsOf(LuaValue.FALSE, LuaValue.valueOf(result.reason.name))
                }
            }
        })
        api.set("granted", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val capability = capability(args.checkjstring(1)) ?: return LuaValue.FALSE
                val target = args.optjstring(2, null)
                return LuaValue.valueOf(interruptible { runBlocking { context.capabilities.isGranted(capability, target) } })
            }
        })
        api.set("time", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(System.currentTimeMillis() / 1000.0)
        })
        api.set("sleep", object : OneArgFunction() {
            override fun call(seconds: LuaValue): LuaValue {
                interruptible { Thread.sleep((seconds.checkdouble() * 1000).toLong().coerceAtLeast(0)) }
                return LuaValue.NONE
            }
        })
        api.set("http", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                val request = args.checktable(1)
                val headers = buildMap {
                    val table = request.get("headers").opttable(LuaTable())
                    table.keys().forEach { put(it.tojstring(), table.get(it).tojstring()) }
                }
                val body = request.get("body").takeUnless { it.isnil() }?.checkstring()?.bytes()
                val response = interruptible {
                    runBlocking {
                        SimpleHttp.request(
                            context.network,
                            request.get("method").optjstring("GET"),
                            request.get("url").checkjstring(),
                            headers,
                            body,
                        )
                    }
                }
                LuaTable().apply {
                    set("status", response.status)
                    set("body", LuaString.valueUsing(response.body))
                    set("headers", LuaTable().also { table -> response.headers.forEach { (k, v) -> table.set(k, v) } })
                }
            }
        })
        val store = LuaTable()
        store.set("read", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                val data = interruptible { runBlocking { context.storage.read(args.checkjstring(1)) } }
                if (data == null) LuaValue.NIL else LuaString.valueUsing(data)
            }
        })
        store.set("write", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                interruptible { runBlocking { context.storage.write(args.checkjstring(1), args.checkstring(2).bytes()) } }
                LuaValue.TRUE
            }
        })
        store.set("delete", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                LuaValue.valueOf(interruptible { runBlocking { context.storage.delete(args.checkjstring(1)) } })
            }
        })
        store.set("list", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                LuaTable().also { table ->
                    interruptible { runBlocking { context.storage.list() } }.forEachIndexed { index, path -> table.set(index + 1, path) }
                }
            }
        })
        api.set("storage", store)
        api.set("notify", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = failingVarargs {
                interruptible { runBlocking { context.notifications.notify(args.checkjstring(1), args.optjstring(2, "")) } }
                LuaValue.TRUE
            }
        })
        api.set("ask", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val secret = args.optboolean(2, false)
                val answer = interruptible { runBlocking { context.prompt.ask(args.checkjstring(1), secret) } }
                return if (answer == null) LuaValue.NIL else LuaValue.valueOf(answer)
            }
        })
        val json = LuaTable()
        json.set("decode", object : OneArgFunction() {
            override fun call(text: LuaValue): LuaValue = LuaJson.decode(text.checkjstring())
        })
        json.set("encode", object : OneArgFunction() {
            override fun call(value: LuaValue): LuaValue = LuaValue.valueOf(LuaJson.encode(value))
        })
        api.set("json", json)
        api.set("urlencode", object : OneArgFunction() {
            override fun call(text: LuaValue): LuaValue =
                LuaValue.valueOf(URLEncoder.encode(text.checkjstring(), "UTF-8").replace("+", "%20"))
        })
        globals.set("mf", api)
        globals.set("print", log)
        return globals
    }

    private fun LuaString.bytes(): ByteArray = ByteArray(m_length).also { copyInto(0, it, 0, m_length) }

    /** Reports host-service failures the Lua way: `nil, message` instead of an error that kills the script. */
    private fun failingVarargs(block: () -> Varargs): Varargs = try {
        block()
    } catch (e: CapabilityNotGrantedException) {
        LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("${e.capability.name} is not granted"))
    } catch (e: IOException) {
        LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(e.message ?: "I/O error"))
    }

    /** Libraries shipped with the runtime, available as `require("mf.<name>")` unless the package has its own. */
    private fun builtin(path: String): InputStream? =
        if (BUILTIN_PATH.matches(path)) LuaScriptModule::class.java.getResourceAsStream("/lua/$path") else null

    private fun capability(name: String): Capability? = Capability.entries.firstOrNull { it.name == name }

    /** Turns thread interruption (module stop) into a Lua error that unwinds the script. */
    private fun <T> interruptible(block: () -> T): T = try {
        block()
    } catch (e: InterruptedException) {
        throw LuaError(e)
    }

    private companion object {
        val BUILTIN_PATH = Regex("""mf/[a-z_]+\.lua""")
    }
}
