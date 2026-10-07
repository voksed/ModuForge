package dev.moduforge.script.py

import dev.moduforge.script.DeviceApi
import dev.moduforge.script.HostFailure
import dev.moduforge.script.HttpCall
import dev.moduforge.script.ScriptHost
import dev.moduforge.script.ScriptSocket
import dev.moduforge.script.ScriptWebSocket
import dev.moduforge.script.Upload
import java.math.BigInteger

/** The module `mf` of a Python script: the host services under their Python names. */
internal class PyHostModule(private val host: ScriptHost) {

    private val errorClass: PyClass by lazy {
        PyClass("Error", listOf(Builtins.exceptionClass("OSError")), linkedMapOf("__module__" to "mf"))
    }

    /** Runs a host call; the failures a script is expected to handle become Python exceptions. */
    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: HostFailure) {
        val message = e.message
        if (message == "timeout") throw PyError.of("TimeoutError", "timed out")
        throw PyError(PyInstance(errorClass, linkedMapOf("args" to PyTuple(listOf(message)))))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw PyInterrupted()
    } catch (e: IllegalArgumentException) {
        throw PyError.of("ValueError", e.message ?: "invalid argument")
    }

    private fun fn(name: String, impl: (Args) -> Any?) = PyBuiltin(name) { a, k -> impl(Args(a, k, name)) }

    private fun obj(type: String, vararg entries: Pair<String, Any?>) = PyObject(type, linkedMapOf(*entries))

    fun create(interp: Interpreter): PyModule {
        val manifest = host.context.manifest
        val module = PyModule("mf")
        val a = module.attrs
        a["id"] = manifest.id
        a["name"] = manifest.name
        a["version"] = manifest.version
        a["Error"] = errorClass

        a["log"] = fn("log") { args -> host.log(args.pos.joinToString(" ") { Py.str(it) }); PyNone }
        a["sleep"] = fn("sleep") { args -> guarded { host.sleep(Py.toDouble(args.req(0, "seconds"))) }; PyNone }
        a["time"] = fn("time") { _ -> host.time() }
        a["date"] = fn("date") { args ->
            host.date(args.opt(0, "format")?.takeIf { it !== PyNone }?.let { Py.str(it) }, args.opt(1, "time")?.takeIf { it !== PyNone }?.let(Py::toDouble))
        }
        a["date_fields"] = fn("date_fields") { args ->
            fromGeneric(host.dateFields(args.opt(0, "time")?.takeIf { it !== PyNone }?.let(Py::toDouble), args.flag("utc")))
        }
        a["request"] = fn("request") { args ->
            guarded { host.request(args.str(0, "permission"), args.str(1, "reason", ""), args.opt(2, "target")?.takeIf { it !== PyNone }?.let { Py.str(it) }) }.first
        }
        a["granted"] = fn("granted") { args ->
            host.granted(args.str(0, "permission"), args.opt(1, "target")?.takeIf { it !== PyNone }?.let { Py.str(it) })
        }
        a["http"] = fn("http") { args -> http(args) }
        a["connect"] = fn("connect") { args ->
            socket(guarded { host.connect(args.str(0, "host"), args.long(1, "port").toInt(), args.flag("tls")) })
        }
        a["websocket"] = fn("websocket") { args ->
            websocket(guarded { host.websocket(args.str(0, "url"), stringMap(args.opt(1, "headers"))) })
        }
        a["storage"] = obj(
            "storage",
            "read" to fn("read") { args -> guarded { host.storageRead(args.str(0, "path")) }?.toString(Charsets.UTF_8) ?: PyNone },
            "read_bytes" to fn("read_bytes") { args -> guarded { host.storageRead(args.str(0, "path")) }?.let(::PyBytes) ?: PyNone },
            "write" to fn("write") { args -> guarded { host.storageWrite(args.str(0, "path"), bytesOf(args.req(1, "data"))) }; true },
            "delete" to fn("delete") { args -> guarded { host.storageDelete(args.str(0, "path")) } },
            "list" to fn("list") { _ -> PyList(guarded { host.storageList() }.toMutableList<Any?>()) },
        )
        a["notify"] = fn("notify") { args -> guarded { host.notify(args.str(0, "title"), args.str(1, "text", "")) }; true }
        a["ask"] = fn("ask") { args -> guarded { host.ask(args.str(0, "question"), args.flag("secret") || (args.pos.getOrNull(1)?.let(Py::truth) ?: false)) } ?: PyNone }
        a["urlencode"] = fn("urlencode") { args -> host.urlencode(args.str(0, "text")) }
        a["ui"] = obj(
            "ui",
            "show" to fn("show") { args -> host.uiShow(toGeneric(args.req(0, "tree"))); PyNone },
            "clear" to fn("clear") { _ -> host.uiShow(null); PyNone },
            "wait" to fn("wait") { args -> guarded { host.uiNext(args.opt(0, "timeout")?.takeIf { it !== PyNone }?.let(Py::toDouble)) }?.let(::fromGeneric) ?: PyNone },
        )
        val algorithms = listOf("md5", "sha1", "sha256", "sha512")
        a["hash"] = PyObject("hash", algorithms.associateWithTo(LinkedHashMap<String, Any?>()) { name ->
            fn(name) { args -> digest(host.hash(name, bytesOf(args.req(0, "data"))), args.opt(1, "raw")) }
        })
        a["hmac"] = PyObject("hmac", algorithms.associateWithTo(LinkedHashMap<String, Any?>()) { name ->
            fn(name) { args -> digest(host.hmac(name, bytesOf(args.req(0, "key")), bytesOf(args.req(1, "data"))), args.opt(2, "raw")) }
        })
        a["base64"] = obj(
            "base64",
            "encode" to fn("encode") { args -> host.base64Encode(bytesOf(args.req(0, "data")), args.opt(1, "url")?.let(Py::truth) ?: false) },
            "decode" to fn("decode") { args -> PyBytes(guarded { host.base64Decode(args.str(0, "text")) }) },
        )
        a["hex"] = obj(
            "hex",
            "encode" to fn("encode") { args -> host.hexEncode(bytesOf(args.req(0, "data"))) },
            "decode" to fn("decode") { args -> PyBytes(guarded { host.hexDecode(args.str(0, "text")) }) },
        )
        a["random"] = fn("random") { args -> PyBytes(guarded { host.randomBytes(args.long(0, "count").toInt()) }) }

        for ((service, calls) in DeviceApi.services) {
            a[service] = PyObject(
                service,
                calls.associateTo(LinkedHashMap<String, Any?>()) { call ->
                    call.name to fn(call.name) { args ->
                        val named: Map<String, Any?> = if (args.kw.isEmpty()) {
                            DeviceApi.named(call, args.pos.map(::toGeneric))
                        } else {
                            DeviceApi.named(call, args.pos.map(::toGeneric)) + args.kw.mapValues { toGeneric(it.value) }
                        }
                        fromGeneric(guarded { host.device(service, call.name, named) })
                    }
                },
            )
        }
        return module
    }

    private fun digest(data: ByteArray, raw: Any?): Any = if (raw != null && Py.truth(raw)) PyBytes(data) else host.hexEncode(data)

    private fun bytesOf(v: Any?): ByteArray = when (v) {
        is PyBytes -> v.data
        is String -> v.toByteArray(Charsets.UTF_8)
        else -> throw Py.error("TypeError", "expected str or bytes, not ${Py.typeName(v)}")
    }

    private fun stringMap(v: Any?): Map<String, String> =
        (v as? PyDict)?.map?.entries?.associate { Py.str(it.key) to Py.str(it.value) }.orEmpty()

    private fun http(args: Args): Any? {
        // `mf.http("https://…", method="POST", …)` or `mf.http({"url": …, …})`.
        val options: Map<String, Any?> = if (args.pos.firstOrNull() is PyDict) {
            (args.pos[0] as PyDict).map.entries.associate { Py.str(it.key) to it.value } + args.kw
        } else {
            args.kw + ("url" to args.req(0, "url"))
        }
        val files = (options["files"] as? PyList)?.items.orEmpty().map { item ->
            val f = item as? PyDict ?: throw Py.error("TypeError", "each file must be a dict")
            fun text(key: String, default: String) = f.map[key]?.let { Py.str(it) } ?: default
            Upload(text("field", "file"), text("filename", "file"), text("type", "application/octet-stream"), bytesOf(f.map["content"] ?: throw Py.error("ValueError", "a file needs content")))
        }
        val response = guarded {
            host.http(
                HttpCall(
                    url = Py.str(options["url"]),
                    method = options["method"]?.let { Py.str(it) } ?: "GET",
                    headers = stringMap(options["headers"]),
                    body = options["body"]?.takeIf { it !== PyNone }?.let(::bytesOf),
                    form = stringMap(options["form"]),
                    files = files,
                    redirects = options["redirects"]?.let(Py::toInt) ?: HttpCall.DEFAULT_REDIRECTS,
                ),
            )
        }
        val text = String(response.body, Charsets.UTF_8)
        return obj(
            "Response",
            "status" to response.status.toLong(),
            "body" to text,
            "text" to text,
            "headers" to PyDict(LinkedHashMap<Any?, Any?>().also { d -> response.headers.forEach { (k, v) -> d[k] = v } }),
            "url" to response.url,
            "bytes" to fn("bytes") { _ -> PyBytes(response.body) },
            "json" to fn("json") { _ -> Py.call(Py.getAttr(interpreterJson(), "loads"), listOf(text)) },
        )
    }

    private fun interpreterJson(): PyModule = Interpreter.current().importModule("json")

    private fun socket(s: ScriptSocket): PyObject = obj(
        "socket",
        "read" to fn("read") { a -> guarded { s.read(a.opt(0, "max")?.let(Py::toInt) ?: 16384, a.opt(1, "timeout")?.takeIf { it !== PyNone }?.let(Py::toDouble)) }?.let(::PyBytes) ?: PyNone },
        "read_exactly" to fn("read_exactly") { a -> guarded { s.readExactly(a.long(0, "count").toInt(), a.opt(1, "timeout")?.takeIf { it !== PyNone }?.let(Py::toDouble)) }?.let(::PyBytes) ?: PyNone },
        "read_line" to fn("read_line") { a -> guarded { s.readLine(a.opt(0, "timeout")?.takeIf { it !== PyNone }?.let(Py::toDouble)) } ?: PyNone },
        "write" to fn("write") { a -> guarded { s.write(bytesOf(a.req(0, "data"))) }; true },
        "close" to fn("close") { _ -> s.close(); PyNone },
        "__enter__" to fn("__enter__") { _ -> PyNone },
        "__exit__" to fn("__exit__") { _ -> s.close(); false },
    )

    private fun websocket(w: ScriptWebSocket): PyObject = obj(
        "websocket",
        "send" to fn("send") { a ->
            val data = a.req(0, "data")
            guarded { if (data is String) w.sendText(data) else w.sendBinary(bytesOf(data)) }
            true
        },
        "ping" to fn("ping") { _ -> guarded { w.ping() }; true },
        "receive" to fn("receive") { a ->
            val message = guarded { w.receive(a.opt(0, "timeout")?.takeIf { it !== PyNone }?.let(Py::toDouble)) }
            if (message == null) PyNone else if (message.text) message.data.toString(Charsets.UTF_8) else PyBytes(message.data)
        },
        "close" to fn("close") { _ -> w.close(); PyNone },
    )

    // --- conversion between script values and plain values --------------------------------------

    fun toGeneric(v: Any?): Any? = when (v) {
        null, PyNone -> null
        is Boolean -> v
        is Long -> v.toDouble()
        is Double -> v
        is BigInteger -> v.toDouble()
        is String -> v
        is PyList -> v.items.map(::toGeneric)
        is PyTuple -> v.items.map(::toGeneric)
        is PyDict -> v.map.entries.associate { Py.str(it.key) to toGeneric(it.value) }
        is PyBytes -> v.data.map { (it.toInt() and 0xFF).toDouble() }
        else -> throw Py.error("TypeError", "a value of type ${Py.typeName(v)} cannot be passed to the host")
    }

    companion object {
        fun fromGeneric(v: Any?): Any? = when (v) {
            null -> PyNone
            is Boolean -> v
            is Int -> v.toLong()
            is Long -> v
            is Double -> v
            is Number -> v.toDouble()
            is String -> v
            is Map<*, *> -> PyDict(LinkedHashMap<Any?, Any?>().also { d -> v.forEach { (k, value) -> d[k.toString()] = fromGeneric(value) } })
            is List<*> -> PyList(v.map(::fromGeneric).toMutableList())
            else -> v.toString()
        }
    }
}
